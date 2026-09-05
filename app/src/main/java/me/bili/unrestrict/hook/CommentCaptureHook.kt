package me.bili.unrestrict.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.*
import me.bili.unrestrict.data.model.CommentFraudStatus
import me.bili.unrestrict.detector.CommentProbeEngine
import me.bili.unrestrict.util.XLog

class CommentCaptureHook(private val module: XposedModule) {

    companion object {
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
                XLog.i("✅ [CommentCapture] $target 挂载成功")
            } catch (t: Throwable) {
                XLog.w("⚠️ [CommentCapture] 挂载失败 $target: ${t.message}")
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

            XLog.i("🎯 [发评拦截] 成功拦截发评: rpid=$rpid, oid=$oid, uid=$uid, msg=\"${message.take(30)}\"")
            startLifecycleWorkflow(rpid, oid, type, root, parent, uid, message, postTime, cookie)
        } catch (e: Exception) {
            XLog.e("❌ [发评拦截] handlePublisherResult 失败: ${e.message}", e)
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
        postTime: Long,
        cookie: String
    ) {
        // 1. 发评瞬间：初态 UNKNOWN 提交入库
        XLog.i("⏳ [发评反诈] 初态 UNKNOWN 入库，将在 4 秒后启动双重视角探针裁决 (rpid=$rpid)")
        sendRecord(rpid, oid, type, root, parent, uid, message, CommentFraudStatus.UNKNOWN.name, postTime, cookie)

        // 2. 后台协程缓冲 4 秒后执行精准路人探测
        hookScope.launch {
            delay(4000L)
            val context = getApplicationContext() ?: return@launch
            val sentAtSec = postTime / 1000L
            val status = CommentProbeEngine.evaluateCommentStatus(context, oid, type, rpid, root, sentAtSec, cookie)
            XLog.i("🏁 [发评反诈] 真实存活定性完成: rpid=$rpid -> $status")

            // 更新真实状态
            sendRecord(rpid, oid, type, root, parent, uid, message, status.name, postTime, cookie)

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
        postTime: Long,
        cookie: String
    ) {
        try {
            val context = getApplicationContext() ?: return

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
            XLog.d("📡 [发评IPC] 广播投递发评记录成功 (rpid=$rpid, status=$status)")
        } catch (e: Exception) {
            XLog.e("❌ [发评IPC] 广播投递异常: ${e.message}", e)
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
