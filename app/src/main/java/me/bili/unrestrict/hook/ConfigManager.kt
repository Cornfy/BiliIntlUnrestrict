package me.bili.unrestrict.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

object ConfigManager {
    private const val TAG = "BiliHook"
    const val ACTION_UPDATE_CONFIG = "me.bili.unrestrict.ACTION_UPDATE_CONFIG"
    const val ACTION_REQUEST_SYNC = "me.bili.unrestrict.ACTION_REQUEST_SYNC"

    @Volatile
    var bypassTeenagerMode: Boolean = true

    @Volatile
    var enableDebugLogging: Boolean = true

    fun init(context: Context) {
        val sp = context.getSharedPreferences("bili_unrestrict_prefs", Context.MODE_PRIVATE)
        bypassTeenagerMode = sp.getBoolean("bypass_teenager_mode", true)
        enableDebugLogging = sp.getBoolean("enable_debug_logging", true)
        Log.i(TAG, "🔧 [ConfigManager] 本地缓存初始值: bypass=$bypassTeenagerMode, log=$enableDebugLogging")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_UPDATE_CONFIG) {
                    if (intent.hasExtra("bypass_teenager_mode")) {
                        val v = intent.getBooleanExtra("bypass_teenager_mode", true)
                        bypassTeenagerMode = v
                        sp.edit().putBoolean("bypass_teenager_mode", v).apply()
                    }
                    if (intent.hasExtra("enable_debug_logging")) {
                        val v = intent.getBooleanExtra("enable_debug_logging", true)
                        enableDebugLogging = v
                        sp.edit().putBoolean("enable_debug_logging", v).apply()
                        Log.i(TAG, "⚡ [ConfigManager] 日志开关已更新: enableDebugLogging=$v")
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_UPDATE_CONFIG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        try {
            val syncIntent = Intent(ACTION_REQUEST_SYNC).apply {
                component = ComponentName("me.bili.unrestrict", "me.bili.unrestrict.ipc.CommentReceiver")
            }
            context.sendBroadcast(syncIntent)
        } catch (_: Exception) {}
    }
}
