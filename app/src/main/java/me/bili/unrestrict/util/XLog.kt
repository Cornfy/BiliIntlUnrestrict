package me.bili.unrestrict.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import me.bili.unrestrict.hook.ConfigManager
import java.util.concurrent.ConcurrentLinkedQueue

object XLog {
    private const val DEFAULT_TAG = "BiliHook"
    private var appContext: Context? = null

    // 暂存启动初期 Context 尚未就绪时的日志 (容量上限 100 条防泄漏)
    private val startupLogBuffer = ConcurrentLinkedQueue<Triple<String, String, String>>()
    private const val MAX_BUFFER_SIZE = 100

    fun init(context: Context) {
        appContext = context.applicationContext
        // 若开关已关闭，直接清空暂存区并退出
        if (!ConfigManager.enableDebugLogging) {
            startupLogBuffer.clear()
            return
        }
        // 冲刷暂存的启动日志
        while (startupLogBuffer.isNotEmpty()) {
            val (tag, level, msg) = startupLogBuffer.poll() ?: break
            sendBroadcastLog(tag, level, msg, appContext)
        }
    }

    fun clearBuffer() {
        startupLogBuffer.clear()
    }

    fun d(msg: String, tag: String = DEFAULT_TAG) {
        if (!ConfigManager.enableDebugLogging) return
        Log.d(tag, msg)
        record(tag, "DEBUG", msg)
    }

    fun i(msg: String, tag: String = DEFAULT_TAG) {
        if (!ConfigManager.enableDebugLogging) return
        Log.i(tag, msg)
        record(tag, "INFO", msg)
    }

    fun w(msg: String, tag: String = DEFAULT_TAG) {
        if (!ConfigManager.enableDebugLogging) return
        Log.w(tag, msg)
        record(tag, "WARN", msg)
    }

    fun e(msg: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) {
        if (!ConfigManager.enableDebugLogging) return
        val fullMsg = if (throwable != null) {
            "$msg\n${throwable.stackTraceToString().take(1000)}"
        } else {
            msg
        }
        Log.e(tag, fullMsg, throwable)
        record(tag, "ERROR", fullMsg)
    }

    private fun record(tag: String, level: String, msg: String) {
        // 严格检查日志开关
        if (!ConfigManager.enableDebugLogging) return

        val ctx = appContext ?: getFallbackContext()
        if (ctx == null) {
            if (startupLogBuffer.size < MAX_BUFFER_SIZE) {
                startupLogBuffer.offer(Triple(tag, level, msg))
            }
        } else {
            sendBroadcastLog(tag, level, msg, ctx)
        }
    }

    private fun getFallbackContext(): Context? {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            val currentApp = clazz.getMethod("currentApplication").invoke(null) as? Context
            if (currentApp != null && appContext == null) {
                appContext = currentApp.applicationContext
            }
            appContext
        } catch (_: Exception) {
            null
        }
    }

    private fun sendBroadcastLog(tag: String, level: String, msg: String, context: Context? = appContext) {
        val ctx = context ?: return
        if (!ConfigManager.enableDebugLogging) return
        try {
            val intent = Intent("me.bili.unrestrict.ACTION_RECORD_LOG").apply {
                component = ComponentName("me.bili.unrestrict", "me.bili.unrestrict.ipc.CommentReceiver")
                putExtra("tag", tag)
                putExtra("level", level)
                putExtra("message", msg)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            ctx.sendBroadcast(intent)
        } catch (_: Exception) {}
    }
}
