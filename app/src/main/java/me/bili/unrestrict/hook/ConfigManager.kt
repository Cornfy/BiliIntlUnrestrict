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

    fun init(context: Context) {
        val sp = context.getSharedPreferences("bili_unrestrict_prefs", Context.MODE_PRIVATE)
        bypassTeenagerMode = sp.getBoolean("bypass_teenager_mode", true)
        Log.i(TAG, "🔧 [ConfigManager] 本地缓存初始值: bypassTeenagerMode=$bypassTeenagerMode")

        // 1. 动态注册监听模块发来的配置广播
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_UPDATE_CONFIG && intent.hasExtra("bypass_teenager_mode")) {
                    val newValue = intent.getBooleanExtra("bypass_teenager_mode", true)
                    bypassTeenagerMode = newValue
                    sp.edit().putBoolean("bypass_teenager_mode", newValue).commit()
                    Log.i(TAG, "⚡ [ConfigManager] 配置已同步更新: bypassTeenagerMode=$newValue")
                }
            }
        }

        val filter = IntentFilter(ACTION_UPDATE_CONFIG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // 🎯 核心解决：冷启动时主动向模块索取最新开关状态 (解决 B 站关闭期间改开关漏听的问题)
        try {
            val syncIntent = Intent(ACTION_REQUEST_SYNC).apply {
                component = ComponentName("me.bili.unrestrict", "me.bili.unrestrict.ipc.CommentReceiver")
            }
            context.sendBroadcast(syncIntent)
            Log.i(TAG, "📡 [ConfigManager] 已向模块请求最新开关状态握手")
        } catch (e: Exception) {
            Log.w(TAG, "请求同步配置失败: ${e.message}")
        }
    }
}
