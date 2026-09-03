package me.bili.unrestrict.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.bili.unrestrict.data.db.AppDatabase
import me.bili.unrestrict.data.db.CommentFraudRecord
import me.bili.unrestrict.data.db.LogRecord
import me.bili.unrestrict.data.repository.CommentFraudRepository

class CommentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSERT = "me.bili.unrestrict.ACTION_INSERT_COMMENT"
        const val ACTION_RECORD_LOG = "me.bili.unrestrict.ACTION_RECORD_LOG"
        const val ACTION_REQUEST_SYNC = "me.bili.unrestrict.ACTION_REQUEST_SYNC"
        const val ACTION_UPDATE_CONFIG = "me.bili.unrestrict.ACTION_UPDATE_CONFIG"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val sp = context.getSharedPreferences("module_config", Context.MODE_PRIVATE)

        // 1. 开关状态同步握手
        if (intent.action == ACTION_REQUEST_SYNC) {
            val bypass = sp.getBoolean("bypass_teenager_mode", true)
            val logEnabled = sp.getBoolean("enable_debug_logging", true)
            val replyIntent = Intent(ACTION_UPDATE_CONFIG).apply {
                setPackage("com.bilibili.app.in")
                putExtra("bypass_teenager_mode", bypass)
                putExtra("enable_debug_logging", logEnabled)
            }
            context.sendBroadcast(replyIntent)
            return
        }

        // 2. 接收日记存库 (增加 goAsync 保证写入完毕前不被系统冻结)
        if (intent.action == ACTION_RECORD_LOG) {
            val tag = intent.getStringExtra("tag") ?: "BiliHook"
            val level = intent.getStringExtra("level") ?: "INFO"
            val message = intent.getStringExtra("message").orEmpty()

            val pending = goAsync()
            scope.launch {
                try {
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    db.logDao().insert(LogRecord(tag = tag, level = level, message = message))
                    db.logDao().pruneOldLogs()
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // 3. 发评数据入库
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
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
