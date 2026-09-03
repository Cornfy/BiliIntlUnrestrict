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

    // 暂存启动初期 Context 尚未就绪时的日志
    private val startupLogBuffer = ConcurrentLinkedQueue<Triple<String, String, String>>()

    fun init(context: Context) {
        appContext = context.applicationContext
        // 冲刷暂存的启动日志
        while (startupLogBuffer.isNotEmpty()) {
            val (tag, level, msg) = startupLogBuffer.poll() ?: break
            sendBroadcastLog(tag, level, msg)
        }
    }

    fun i(msg: String, tag: String = DEFAULT_TAG) {
        Log.i(tag, msg)
        record(tag, "INFO", msg)
    }

    fun w(msg: String, tag: String = DEFAULT_TAG) {
        Log.w(tag, msg)
        record(tag, "WARN", msg)
    }

    fun e(msg: String, tag: String = DEFAULT_TAG) {
        Log.e(tag, msg)
        record(tag, "ERROR", msg)
    }

    private fun record(tag: String, level: String, msg: String) {
        if (!ConfigManager.enableDebugLogging) return

        if (appContext == null) {
            startupLogBuffer.offer(Triple(tag, level, msg))
        } else {
            sendBroadcastLog(tag, level, msg)
        }
    }

    private fun sendBroadcastLog(tag: String, level: String, msg: String) {
        val ctx = appContext ?: return
        try {
            val intent = Intent("me.bili.unrestrict.ACTION_RECORD_LOG").apply {
                component = ComponentName("me.bili.unrestrict", "me.bili.unrestrict.ipc.CommentReceiver")
                putExtra("tag", tag)
                putExtra("level", level)
                putExtra("message", msg)
            }
            ctx.sendBroadcast(intent)
        } catch (_: Exception) {}
    }
}
