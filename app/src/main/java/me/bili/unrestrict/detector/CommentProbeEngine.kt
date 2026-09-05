package me.bili.unrestrict.detector

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bili.unrestrict.data.model.CommentFraudStatus
import me.bili.unrestrict.util.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.log2

object CommentProbeEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private suspend fun fetch(url: String, cookie: String = ""): String? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Origin", "https://www.bilibili.com")
                .header("Referer", "https://www.bilibili.com")
                .header("Accept", "application/json, text/plain, */*")

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

    /**
     * 统一入口：根据 root 是否为 0 分发给根评论或楼中楼算法
     */
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
        if (root == 0L) {
            evaluateRootComment(oid, type, rpid, effectiveCookie)
        } else {
            evaluateSubReply(oid, type, rpid, root, sentAtSeconds, effectiveCookie)
        }
    }

    /**
     * 根评论检测 (双重视角裁决)
     */
    private suspend fun evaluateRootComment(
        oid: Long,
        type: Int,
        rpid: Long,
        cookie: String
    ): CommentFraudStatus {
        val rpidPattern = """"rpid":\s*$rpid""".toRegex()
        val url = "https://api.bilibili.com/x/v2/reply/main?oid=$oid&type=$type&mode=2&next=0&seek_rpid=$rpid&ps=20"

        XLog.i("🔎 [存活探针-根评] 开始路人视角探测: oid=$oid, rpid=$rpid")

        // 1. 纯路人视角查
        val anonJson = fetch(url, cookie = "")
        if (anonJson != null && rpidPattern.containsMatchIn(anonJson)) {
            val isInvisible = anonJson.contains(""""rpid":\s*$rpid[^}]*?"invisible":\s*true""".toRegex())
            val status = if (isInvisible) CommentFraudStatus.INVISIBLE else CommentFraudStatus.NORMAL
            XLog.i("🟢 [存活探针-根评] 路人视角检索命中: rpid=$rpid -> $status")
            return status
        }

        // 2. 路人未找到，作者视角复验 (核心区别 ShadowBan 与 Deleted)
        XLog.w("⚠️ [存活探针-根评] 路人视角未找到 rpid=$rpid，启动作者视角复验 (Cookie=${if (cookie.isNotBlank()) "已携带" else "缺失"})...")
        val authJson = if (cookie.isNotBlank()) fetch(url, cookie = cookie) else null
        val authFound = authJson != null && rpidPattern.containsMatchIn(authJson)

        val finalStatus = if (authFound) {
            CommentFraudStatus.SHADOW_BANNED
        } else {
            CommentFraudStatus.DELETED
        }

        if (finalStatus == CommentFraudStatus.SHADOW_BANNED) {
            XLog.w("🚨 [存活探针-根评] 判定为仅自己可见 (SHADOW_BANNED): 路人不可见但作者视角可见 (rpid=$rpid)")
        } else {
            XLog.w("🗑️ [存活探针-根评] 判定为已被系统秒删 (DELETED): 双重视角均未检索到 (rpid=$rpid)")
        }
        return finalStatus
    }

    /**
     * 楼中楼检测 (自适应二分 + 目标页作者双重复验)
     */
    private suspend fun evaluateSubReply(
        oid: Long,
        type: Int,
        rpid: Long,
        root: Long,
        sentAtSeconds: Long,
        cookie: String
    ): CommentFraudStatus {
        val rpidPattern = """"rpid":\s*$rpid""".toRegex()
        val urlBase = "https://api.bilibili.com/x/v2/reply/reply?oid=$oid&type=$type&root=$root&ps=20&sort=0"

        XLog.i("🔎 [存活探针-楼中楼] 开始探测: oid=$oid, root=$root, rpid=$rpid")

        val firstPageJson = fetch("$urlBase&pn=1", cookie = "")
        if (firstPageJson?.contains("\"code\":12022") == true) {
            XLog.w("🗑️ [存活探针-楼中楼] 根评论已被删除(code=12022)，定性为 DELETED (rpid=$rpid)")
            return CommentFraudStatus.DELETED
        }

        // 提取总量并计算末页
        val rcountMatch = Regex(""""rcount":\s*(\d+)""").find(firstPageJson.orEmpty())
        val countMatch = Regex(""""count":\s*(\d+)""").find(firstPageJson.orEmpty())
        val totalCount = maxOf(
            rcountMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            20
        )
        val lastPage = maxOf(1, (totalCount + 19) / 20)

        var found = false
        var invisible = false
        var targetPage = lastPage

        XLog.d("📄 [存活探针-楼中楼] 根评子评论总数: $totalCount, 计算末页: $lastPage")

        // 1. 优先直跳末页探测
        val lastPageJson = fetch("$urlBase&pn=$lastPage", cookie = "")
        if (lastPageJson != null && rpidPattern.containsMatchIn(lastPageJson)) {
            found = true
            invisible = lastPageJson.contains(""""rpid":\s*$rpid[^}]*?"invisible":\s*true""".toRegex())
            XLog.i("🟢 [存活探针-楼中楼] 直跳末页($lastPage)命中: rpid=$rpid (invisible=$invisible)")
        } else if (lastPage > 1) {
            // 倒数第 2 页容差
            val prevJson = fetch("$urlBase&pn=${lastPage - 1}", cookie = "")
            if (prevJson != null && rpidPattern.containsMatchIn(prevJson)) {
                found = true
                invisible = prevJson.contains(""""rpid":\s*$rpid[^}]*?"invisible":\s*true""".toRegex())
                targetPage = lastPage - 1
                XLog.i("🟢 [存活探针-楼中楼] 倒数第2页(${lastPage - 1})命中: rpid=$rpid (invisible=$invisible)")
            }
        }

        // 2. 自适应单调时序折半收敛 (应对数千楼大楼)
        if (!found && lastPage > 2 && sentAtSeconds > 0L) {
            XLog.d("⚡ [存活探针-楼中楼] 启动二分时序折半探测: 1~$lastPage, sentAt=$sentAtSeconds")
            var low = 1
            var high = lastPage - 2
            var steps = 0
            val maxSteps = minOf(8, (log2(lastPage.toDouble()).toInt() + 2))

            while (low <= high && steps < maxSteps) {
                steps++
                val mid = (low + high) / 2
                val midJson = fetch("$urlBase&pn=$mid", cookie = "") ?: break

                if (rpidPattern.containsMatchIn(midJson)) {
                    found = true
                    invisible = midJson.contains(""""rpid":\s*$rpid[^}]*?"invisible":\s*true""".toRegex())
                    targetPage = mid
                    XLog.i("🟢 [存活探针-楼中楼] 二分折半第 $steps 步命中 (pn=$mid, rpid=$rpid)")
                    break
                }

                val ctimes = Regex(""""ctime":\s*(\d+)""").findAll(midJson)
                    .mapNotNull { it.groupValues[1].toLongOrNull() }
                    .toList()
                val minCtime = ctimes.minOrNull() ?: 0L
                val maxCtime = ctimes.maxOrNull() ?: 0L

                if (minCtime > 0L && maxCtime > 0L) {
                    if (sentAtSeconds < minCtime) high = mid - 1
                    else if (sentAtSeconds > maxCtime) low = mid + 1
                    else {
                        targetPage = mid
                        break
                    }
                } else break
            }
        }

        // 3. 最终判定
        if (found) {
            val status = if (invisible) CommentFraudStatus.INVISIBLE else CommentFraudStatus.NORMAL
            XLog.i("🏁 [存活探针-楼中楼] 路人视角定性完成: rpid=$rpid -> $status")
            return status
        }

        // 4. 作者视角对【目标页 targetPage】双重复验
        XLog.w("⚠️ [存活探针-楼中楼] 路人视角未找到，正在对目标页 pn=$targetPage 进行作者视角复验...")
        val authJson = if (cookie.isNotBlank()) fetch("$urlBase&pn=$targetPage", cookie = cookie) else null
        var authFound = authJson != null && rpidPattern.containsMatchIn(authJson)

        if (!authFound && targetPage > 1 && cookie.isNotBlank()) {
            val authPrev = fetch("$urlBase&pn=${targetPage - 1}", cookie = cookie)
            if (authPrev != null && rpidPattern.containsMatchIn(authPrev)) {
                authFound = true
            }
        }

        val finalStatus = if (authFound) {
            CommentFraudStatus.SHADOW_BANNED
        } else {
            CommentFraudStatus.DELETED
        }

        if (finalStatus == CommentFraudStatus.SHADOW_BANNED) {
            XLog.w("🚨 [存活探针-楼中楼] 判定为仅自己可见 (SHADOW_BANNED): 作者视角在 pn=$targetPage 可见 (rpid=$rpid)")
        } else {
            XLog.w("🗑️ [存活探针-楼中楼] 判定为已被系统秒删 (DELETED): 楼中楼双重视角均未检索到 (rpid=$rpid)")
        }
        return finalStatus
    }
}
