package me.bili.unrestrict.detector

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.bili.unrestrict.data.model.CommentFraudStatus
import me.bili.unrestrict.util.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.log2

/**
 * 发评存活探针：双重视角（路人 / 作者）差分裁决。
 *
 * 纯解读与裁定逻辑都在 [ProbeInterpreter]（可单测）；这里只负责网络与调度：
 *   1) 请求一律带时间戳，绕开 CDN 对匿名响应的缓存
 *   2) "请求失败"与"该视角确定没有"严格区分，失败即判 UNKNOWN，绝不落锤
 *   3) 缺少作者视角 Cookie 时，"仅自己可见"与"已删除"无法区分，判 UNKNOWN
 *   4) 落锤"仅自己可见"前，延迟复验一次路人视角，排除缓存 / 审核延迟造成的假阳性
 */
object CommentProbeEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private const val RECHECK_DELAY_MS = 1500L   // 落锤"仅自己可见"前复验路人视角的间隔
    private const val BINARY_STEP_CAP = 8        // 时序二分的步数上限
    private const val BINARY_STEP_MARGIN = 2     // 时序二分的步数余量

    private suspend fun fetch(url: String, cookie: String = ""): String? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Origin", "https://www.bilibili.com")
                .header("Referer", "https://www.bilibili.com")
                .header("Accept", "application/json, text/plain, */*")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")

            if (cookie.isNotBlank()) {
                builder.header("Cookie", cookie)
            }

            val response = client.newCall(builder.build()).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                XLog.w("⚠️ [探针网络] HTTP 请求失败 code=${response.code}, url=$url")
                null
            }
        } catch (e: Exception) {
            XLog.w("⚠️ [探针网络] 请求异常 url=$url: ${e.message}")
            null
        }
    }

    private suspend fun probeOnce(url: String, cookie: String, rpid: Long): ProbeReading =
        ProbeInterpreter.readingOf(fetch(url, cookie), rpid)

    private fun statusOf(reading: ProbeReading): CommentFraudStatus =
        if (reading.invisible) CommentFraudStatus.INVISIBLE else CommentFraudStatus.NORMAL

    private fun statusOf(verdict: ProbeVerdict): CommentFraudStatus = when (verdict) {
        ProbeVerdict.NORMAL -> CommentFraudStatus.NORMAL
        ProbeVerdict.INVISIBLE -> CommentFraudStatus.INVISIBLE
        ProbeVerdict.SHADOW_BANNED -> CommentFraudStatus.SHADOW_BANNED
        ProbeVerdict.DELETED -> CommentFraudStatus.DELETED
        ProbeVerdict.UNKNOWN -> CommentFraudStatus.UNKNOWN
    }

    private fun resolveCookie(context: Context?, providedCookie: String): String {
        if (providedCookie.isNotBlank()) return providedCookie
        val fromWeb = try {
            CookieManager.getInstance().getCookie("https://bilibili.com").orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (fromWeb.isNotBlank()) return fromWeb
        return try {
            context?.getSharedPreferences("module_config", Context.MODE_PRIVATE)?.getString("bili_cookie", "").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** 统一入口：根据 root 是否为 0 分发给根评论或楼中楼算法 */
    suspend fun evaluateCommentStatus(
        context: Context? = null,
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long = 0L,
        sentAtSeconds: Long = 0L,
        cookie: String = ""
    ): CommentFraudStatus = withContext(Dispatchers.IO) {
        val effectiveCookie = resolveCookie(context, cookie)
        // 个别版本会把根评论的 root 填成它自己的 rpid，兜一下，避免误入楼中楼分支
        val rootId = if (root != 0L && root != rpid) root else 0L
        if (rootId == 0L) {
            evaluateRootComment(oid, type, rpid, effectiveCookie)
        } else {
            evaluateSubReply(oid, type, rpid, rootId, sentAtSeconds, effectiveCookie)
        }
    }

    /**
     * 根评论检测：用 seek_rpid 单请求锚定目标，不依赖"最新排序的前 N 条"。
     */
    private suspend fun evaluateRootComment(
        oid: Long,
        type: Int,
        rpid: Long,
        cookie: String
    ): CommentFraudStatus {
        fun url(): String = "https://api.bilibili.com/x/v2/reply/main?oid=$oid&type=$type" +
            "&mode=2&next=0&seek_rpid=$rpid&ps=20&_=${System.currentTimeMillis()}"

        XLog.i("🔎 [存活探针-根评] 开始路人视角探测: oid=$oid, rpid=$rpid")
        val anon = probeOnce(url(), "", rpid)

        if (anon.hit) {
            val status = statusOf(anon)
            XLog.i("🟢 [存活探针-根评] 路人视角检索命中: rpid=$rpid -> $status")
            return status
        }
        if (anon.kind == ProbeKind.UNAVAILABLE) {
            XLog.w("⚠️ [存活探针-根评] 路人视角不可用 (code=${anon.code})，本轮不判定 rpid=$rpid")
            return CommentFraudStatus.UNKNOWN
        }

        val auth = if (cookie.isBlank()) null else probeOnce(url(), cookie, rpid)
        if (auth == null) {
            val verdict = ProbeInterpreter.verdictOf(anon, null)
            XLog.w(
                if (verdict == ProbeVerdict.DELETED)
                    "🗑️ [存活探针-根评] 评论区不存在 (code=${anon.code}) -> DELETED (rpid=$rpid)"
                else
                    "⚠️ [存活探针-根评] 缺少作者视角 Cookie，无法区分「仅自己可见」与「已删除」 -> UNKNOWN (rpid=$rpid)"
            )
            return statusOf(verdict)
        }

        XLog.w("⚠️ [存活探针-根评] 路人视角未找到 rpid=$rpid，启动作者视角复验...")
        val verdict = ProbeInterpreter.verdictOf(anon, auth)

        if (verdict == ProbeVerdict.SHADOW_BANNED) {
            // 候选结论：延迟复验路人视角，排除缓存 / 审核延迟造成的假阳性
            delay(RECHECK_DELAY_MS)
            val recheck = probeOnce(url(), "", rpid)
            val confirmed = ProbeInterpreter.verdictOf(anon, auth, recheck)
            XLog.w(
                when (confirmed) {
                    ProbeVerdict.SHADOW_BANNED -> "🚨 [存活探针-根评] 判定为仅自己可见 (SHADOW_BANNED): 路人不可见但作者可见 (rpid=$rpid)"
                    ProbeVerdict.UNKNOWN -> "⚠️ [存活探针-根评] 复验路人视角不可用 (code=${recheck.code}) -> UNKNOWN (rpid=$rpid)"
                    else -> "🟢 [存活探针-根评] 复验路人视角可见 -> ${statusOf(confirmed)} (rpid=$rpid)"
                }
            )
            return statusOf(confirmed)
        }

        XLog.w(
            if (verdict == ProbeVerdict.UNKNOWN)
                "⚠️ [存活探针-根评] 作者视角不可用 (code=${auth.code}) -> UNKNOWN (rpid=$rpid)"
            else
                "🗑️ [存活探针-根评] 双重视角均未见 -> DELETED (rpid=$rpid)"
        )
        return statusOf(verdict)
    }

    /**
     * 楼中楼检测。
     * 该接口既不支持 seek_rpid，ps 也被服务端锁死在 20，只能翻页：
     *   1) 末页直跳 + 倒数第 2 页容差
     *   2) 按发布时间做时序二分 —— 区间只取自 data.replies，且命中区间时核实相邻页而非直接落锤
     *   3) 扫描不完整且楼层不大时全量翻页兜底，否则判 UNKNOWN，绝不误报
     */
    private suspend fun evaluateSubReply(
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long,
        sentAtSeconds: Long,
        cookie: String
    ): CommentFraudStatus {
        val urlBase = "https://api.bilibili.com/x/v2/reply/reply?oid=$oid&type=$type&root=$root&ps=20&sort=0"

        suspend fun probe(pn: Int, asAuthor: Boolean = false): ProbeReading =
            probeOnce("$urlBase&pn=$pn&_=${System.currentTimeMillis()}", if (asAuthor) cookie else "", rpid)

        XLog.i("🔎 [存活探针-楼中楼] 开始探测: oid=$oid, root=$root, rpid=$rpid")

        val first = probe(1)
        if (first.hit) {
            val status = statusOf(first)
            XLog.i("🟢 [存活探针-楼中楼] 首页命中: rpid=$rpid -> $status")
            return status
        }
        if (first.kind == ProbeKind.UNAVAILABLE) {
            XLog.w("⚠️ [存活探针-楼中楼] 路人视角不可用 (code=${first.code})，本轮不判定 rpid=$rpid")
            return CommentFraudStatus.UNKNOWN
        }
        if (first.kind == ProbeKind.GONE) {
            XLog.w("🗑️ [存活探针-楼中楼] 评论区不存在 (code=${first.code})，复验作者视角...")
            val auth = if (cookie.isBlank()) null else probe(1, asAuthor = true)
            val verdict = ProbeInterpreter.verdictOf(first, auth)
            XLog.w("🏁 [存活探针-楼中楼] 判定 rpid=$rpid -> ${statusOf(verdict)}")
            return statusOf(verdict)
        }

        val lastPage = ProbeInterpreter.lastPage(first.totalCount, first.pageSize)
        var targetPage = lastPage
        var hitProbe: ProbeReading? = null
        // 没有发布时间 → 时序二分用不了，末两页的"未见"不足为凭
        var scanIncomplete = lastPage > 2 && sentAtSeconds <= 0L
        var binarySteps = 0

        XLog.d("📄 [存活探针-楼中楼] 子评论总数=${first.totalCount} 页大小=${first.pageSize} 计算末页=$lastPage")

        // 1. 末页直跳 + 倒数第 2 页容差
        val tail = probe(lastPage)
        if (!tail.ok) scanIncomplete = true
        if (tail.hit) {
            hitProbe = tail
        } else if (lastPage > 1) {
            val prev = probe(lastPage - 1)
            if (!prev.ok) scanIncomplete = true
            if (prev.hit) {
                hitProbe = prev
                targetPage = lastPage - 1
            }
        }

        // 2. 时序二分：区间只取自 data.replies；命中区间时核实相邻页而非直接落锤
        if (hitProbe == null && !scanIncomplete && lastPage > 2 && sentAtSeconds > 0L) {
            val candidates = mutableListOf<Int>()
            // 步数预算：log2(页数)+2 次请求足以收敛，上限 8
            // —— 这是「减少查询次数」的关键优化，别退回固定步数
            val maxSteps = minOf(BINARY_STEP_CAP, log2(lastPage.toDouble()).toInt() + BINARY_STEP_MARGIN)
            var low = 1
            var high = lastPage - 2

            while (low <= high && binarySteps < maxSteps) {
                binarySteps++
                val mid = (low + high) / 2
                val midProbe = probe(mid)
                if (!midProbe.ok) {
                    scanIncomplete = true
                    break
                }
                if (midProbe.hit) {
                    hitProbe = midProbe
                    targetPage = mid
                    XLog.i("🟢 [存活探针-楼中楼] 时序二分第 $binarySteps 步命中 (pn=$mid, rpid=$rpid)")
                    break
                }

                val range = midProbe.ctimeRange
                if (range == null) {
                    scanIncomplete = true
                    break
                }
                when {
                    sentAtSeconds < range.first -> high = mid - 1
                    sentAtSeconds > range.second -> low = mid + 1
                    else -> {
                        // 时间落在本页区间内却没找到：目标多半在相邻页边界上，登记候选后逐个核实
                        candidates.add(mid - 1)
                        candidates.add(mid)
                        candidates.add(mid + 1)
                        break
                    }
                }
            }

            for (page in candidates) {
                if (hitProbe != null) break
                if (page < 1 || page > lastPage) continue
                val candidate = probe(page)
                if (!candidate.ok) {
                    scanIncomplete = true
                    break
                }
                if (candidate.hit) {
                    hitProbe = candidate
                    targetPage = page
                }
            }
            // 区间命中却相邻页都没命中 → 这次定位不可信：宁可不判（未知分组），绝不误报
            if (hitProbe == null && candidates.isNotEmpty() && !scanIncomplete) {
                scanIncomplete = true
                XLog.w("⚠️ [存活探针-楼中楼] 区间命中但候选页均未命中（定位不可信），本轮不落锤 rpid=$rpid")
            }
            XLog.d("⚡ [存活探针-楼中楼] 时序二分结束: steps=$binarySteps, targetPage=$targetPage")
        }

        // 注：此处原有「扫描不完整时全量翻页兜底」，已移除。
        // 它只为把 UNKNOWN 救成确定结论，代价是在不确定时多打一串请求；
        // 正确性优先 —— 拿不准就归入「未知」分组，交给专门的补判逻辑，不做穷举。

        if (hitProbe != null) {
            val status = statusOf(hitProbe)
            XLog.i("🏁 [存活探针-楼中楼] 路人视角定性完成: rpid=$rpid -> $status (pn=$targetPage)")
            return status
        }

        // 4. 路人未见，但扫描不完整时不足为凭
        if (scanIncomplete) {
            XLog.w("⚠️ [存活探针-楼中楼] 路人视角扫描不完整（共 $lastPage 页），本轮不落锤 rpid=$rpid")
            return CommentFraudStatus.UNKNOWN
        }

        // 5. 作者视角对目标页复验（含上一页容差）
        if (cookie.isBlank()) {
            XLog.w("⚠️ [存活探针-楼中楼] 缺少作者视角 Cookie，无法区分「仅自己可见」与「已删除」 -> UNKNOWN (rpid=$rpid)")
            return CommentFraudStatus.UNKNOWN
        }

        XLog.w("⚠️ [存活探针-楼中楼] 路人视角未见，正在对目标页 pn=$targetPage 进行作者视角复验...")
        val auth = probe(targetPage, asAuthor = true)
        var authFound = auth.hit
        if (!authFound && auth.ok && targetPage > 1) {
            authFound = probe(targetPage - 1, asAuthor = true).hit
        }

        val anonLack = ProbeReading(ProbeKind.OK)              // 路人视角已完整扫描，确实未见
        val authReading = if (authFound) ProbeReading(ProbeKind.OK, hit = true) else auth
        val verdict = ProbeInterpreter.verdictOf(anonLack, authReading)

        if (verdict == ProbeVerdict.SHADOW_BANNED) {
            // 候选结论：延迟复验路人视角，排除缓存 / 审核延迟造成的假阳性
            delay(RECHECK_DELAY_MS)
            val recheck = probe(targetPage)
            val confirmed = ProbeInterpreter.verdictOf(anonLack, authReading, recheck)
            XLog.w(
                when (confirmed) {
                    ProbeVerdict.SHADOW_BANNED -> "🚨 [存活探针-楼中楼] 判定为仅自己可见 (SHADOW_BANNED): 作者视角在 pn=$targetPage 可见 (rpid=$rpid)"
                    ProbeVerdict.UNKNOWN -> "⚠️ [存活探针-楼中楼] 复验路人视角不可用 (code=${recheck.code}) -> UNKNOWN (rpid=$rpid)"
                    else -> "🟢 [存活探针-楼中楼] 复验路人视角可见 -> ${statusOf(confirmed)} (rpid=$rpid)"
                }
            )
            return statusOf(confirmed)
        }

        XLog.w(
            if (verdict == ProbeVerdict.UNKNOWN)
                "⚠️ [存活探针-楼中楼] 作者视角不可用 (code=${auth.code}) -> UNKNOWN (rpid=$rpid)"
            else
                "🗑️ [存活探针-楼中楼] 双重视角均未见 -> DELETED (rpid=$rpid)"
        )
        return statusOf(verdict)
    }
}
