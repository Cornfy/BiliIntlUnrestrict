package me.bili.unrestrict.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.*
import me.bili.unrestrict.data.model.CommentFraudStatus
import me.bili.unrestrict.detector.CommentProbeEngine

class CommentCaptureHook(private val module: XposedModule) {

    companion object {
        private const val TAG = "BiliHook"
        private val hookScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())

        fun showToast(context: Context?, text: String) {
            if (context == null) return
            mainHandler.post {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun install(classLoader: ClassLoader) {
        val targets = listOf(
            "com.bilibili.app.comment3.data.source.v1.PublisherDataSourceV1\$post\$2",
            "com.bilibili.app.comment3.data.source.v1.PublisherDataSourceV1\$lightPost\$2"
        )

        for (target in targets) {
            try {
                val clazz = classLoader.loadClass(target)
                clazz.getDeclaredMethod("invokeSuspend", Any::class.java).let { method ->
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (result != null) {
                            handlePublisherResult(result)
                        }
                        result
                    }
                }
                Log.i(TAG, "✅ [CommentCapture] $target 挂载成功")
            } catch (t: Throwable) {
                Log.w(TAG, "⚠️ 挂载失败 $target: ${t.message}")
            }
        }
    }

    private fun handlePublisherResult(replyObj: Any) {
        try {
            val dField = replyObj.javaClass.getField("d")
            val commentItem = dField.get(replyObj) ?: return
            val itemClass = commentItem.javaClass
            val itemStr = commentItem.toString()

            val rpid = itemClass.getField("a").getLong(commentItem)
            val oid = itemClass.getField("b").getLong(commentItem)
            val type = itemClass.getField("c").getLong(commentItem).toInt()
            val root = itemClass.getField("d").getLong(commentItem)
            val parent = itemClass.getField("e").getLong(commentItem)

            // 🎯 用户 UID 抓取
            val cookie = try {
                android.webkit.CookieManager.getInstance().getCookie("https://bilibili.com").orEmpty()
            } catch (_: Exception) { "" }

            val uidFromItem = Regex("""mid=(\d+)""").find(itemStr)?.groupValues?.get(1)?.toLongOrNull()
            val uidFromCookie = Regex("""DedeUserID=(\d+)""").find(cookie)?.groupValues?.get(1)?.toLongOrNull()
            val uid = uidFromItem ?: uidFromCookie ?: 0L

            // 🎯 时间戳
            val rawSeconds = try {
                itemClass.getField("g").getLong(commentItem)
            } catch (_: Exception) { 0L }
            val postTime = if (rawSeconds > 0L) rawSeconds * 1000L else System.currentTimeMillis()

            // 🎯 【终极根治】支持换行长评提取，且坚决排除 foldInfo 干扰！
            val message = extractRealMessage(itemStr)

            Log.i(TAG, "🎯 [Publisher] 提取发评: rpid=$rpid, oid=$oid, uid=$uid, time=$postTime, msg=$message")
            startLifecycleWorkflow(rpid, oid, type, root, parent, uid, message, postTime)
        } catch (e: Exception) {
            Log.e(TAG, "❌ handlePublisherResult 失败: ${e.message}")
        }
    }

    /**
     * 多行安全文案提取器
     */
    private fun extractRealMessage(itemStr: String): String {
        // 1. 优先从 originalContent 精准提取 (开启 DOT_MATCHES_ALL 支持换行)
        val origMatch = Regex(
            """originalContent=.*?RichText\(raw=(.*?)(?:,\s*contents=|\))""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(itemStr)

        val msg1 = origMatch?.groupValues?.get(1)?.trim()
        if (!msg1.isNullOrBlank() && !msg1.contains("已折叠")) {
            return msg1
        }

        // 2. 备选方案：遍历所有 RichText 块，排除系统折叠提示
        val allMatches = Regex(
            """RichText\(raw=(.*?)(?:,\s*contents=|\))""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).findAll(itemStr)

        for (m in allMatches) {
            val candidate = m.groupValues[1].trim()
            if (candidate.isNotBlank() && !candidate.contains("已折叠")) {
                return candidate
            }
        }

        return "已发表评论"
    }

    private fun startLifecycleWorkflow(
        rpid: Long,
        oid: Long,
        type: Int,
        root: Long,
        parent: Long,
        uid: Long,
        message: String,
        postTime: Long
    ) {
        // 1. 发评瞬间：初态 UNKNOWN 提交入库
        sendRecord(rpid, oid, type, root, parent, uid, message, CommentFraudStatus.UNKNOWN.name, postTime)

        // 2. 后台协程缓冲 4 秒后执行精准路人探测
        hookScope.launch {
            delay(4000L)
            val context = getApplicationContext() ?: return@launch // 👈 优先获取 context
            val sentAtSec = postTime / 1000L
            val status = CommentProbeEngine.evaluateCommentStatus(context, oid, type, rpid, root, sentAtSec)
            Log.i(TAG, "🔍 [CommentCapture] 最终定性: rpid=$rpid -> $status")

            // 更新真实状态
            sendRecord(rpid, oid, type, root, parent, uid, message, status.name, postTime)

            // 3. 前台 Toast 反馈
            when (status) {
                CommentFraudStatus.NORMAL -> showToast(context, "🟢 发评反诈: 评论正常 (路人可见)")
                CommentFraudStatus.SHADOW_BANNED -> showToast(context, "🔴 发评反诈: 评论被仅自己可见 (ShadowBan)")
                CommentFraudStatus.DELETED -> showToast(context, "⚫ 发评反诈: 评论已被系统秒删")
                CommentFraudStatus.UNDER_REVIEW -> showToast(context, "🟡 发评反诈: 评论疑似进入审核队列")
                CommentFraudStatus.INVISIBLE -> showToast(context, "🟠 发评反诈: 评论被软屏蔽 (Invisible)")
                else -> {}
            }
        }
    }

    private fun sendRecord(
        rpid: Long,
        oid: Long,
        type: Int,
        root: Long,
        parent: Long,
        uid: Long,
        message: String,
        status: String,
        postTime: Long
    ) {
        try {
            val context = getApplicationContext() ?: return

            // 自动提取 B 站宿主当前的登录 Cookie (包含 SESSDATA 和 bili_jct)
            val cookie = try {
                android.webkit.CookieManager.getInstance().getCookie("https://bilibili.com").orEmpty()
            } catch (_: Exception) { "" }

            val intent = Intent("me.bili.unrestrict.ACTION_INSERT_COMMENT").apply {
                component = ComponentName("me.bili.unrestrict", "me.bili.unrestrict.ipc.CommentReceiver")
                putExtra("rpid", rpid)
                putExtra("oid", oid)
                putExtra("type", type)
                putExtra("root", root)
                putExtra("parent", parent)
                putExtra("uid", uid)
                putExtra("message", message)
                putExtra("status", status)
                putExtra("post_time", postTime)
                putExtra("bili_cookie", cookie)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 广播投递异常: ${e.message}")
        }
    }

    private fun getApplicationContext(): Context? {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            clazz.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Exception) {
            null
        }
    }
}
