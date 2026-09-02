package me.bili.unrestrict.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.bili.unrestrict.data.db.CommentFraudRecord
import me.bili.unrestrict.data.repository.CommentFraudRepository

class CommentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSERT = "me.bili.unrestrict.ACTION_INSERT_COMMENT"
        const val ACTION_REQUEST_SYNC = "me.bili.unrestrict.ACTION_REQUEST_SYNC"
        const val ACTION_UPDATE_CONFIG = "me.bili.unrestrict.ACTION_UPDATE_CONFIG"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val sp = context.getSharedPreferences("module_config", Context.MODE_PRIVATE)

        // 🎯 核心握手响应：B 站冷启动向模块索取配置，模块立即把当前真实的开关回传给 B 站！
        if (intent.action == ACTION_REQUEST_SYNC) {
            val currentSetting = sp.getBoolean("bypass_teenager_mode", true)
            val replyIntent = Intent(ACTION_UPDATE_CONFIG).apply {
                setPackage("com.bilibili.app.in")
                putExtra("bypass_teenager_mode", currentSetting)
            }
            context.sendBroadcast(replyIntent)
            Log.i("BiliHook", "🤝 [CommentReceiver] 已向 B 站回传最新真实开关: bypassTeenagerMode=$currentSetting")
            return
        }

        // 发评数据入库逻辑
        if (intent.action == ACTION_INSERT) {
            val rpid = intent.getLongExtra("rpid", 0L)
            if (rpid <= 0L) return

            val oid = intent.getLongExtra("oid", 0L)
            val type = intent.getIntExtra("type", 1)
            val root = intent.getLongExtra("root", 0L)
            val parent = intent.getLongExtra("parent", 0L)
            val uid = intent.getLongExtra("uid", 0L)
            val message = intent.getStringExtra("message").orEmpty()
            val status = intent.getStringExtra("status") ?: "UNKNOWN"
            val postTime = intent.getLongExtra("post_time", 0L)
            val cookie = intent.getStringExtra("bili_cookie")

            if (!cookie.isNullOrBlank()) {
                sp.edit().putString("bili_cookie", cookie).apply()
            }

            val pendingResult = goAsync()
            scope.launch {
                try {
                    val record = CommentFraudRecord(
                        rpid = rpid,
                        oid = oid,
                        type = type,
                        root = root,
                        parent = parent,
                        uid = uid,
                        message = message,
                        status = status,
                        initial_status = status,
                        post_time = postTime
                    )
                    CommentFraudRepository.saveRecord(context.applicationContext, record)
                    Log.i("BiliHook", "🎉 [私有库] 写入成功: rpid=$rpid, msg=$message")
                } catch (e: Exception) {
                    Log.e("BiliHook", "❌ [私有库] 写入失败: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
