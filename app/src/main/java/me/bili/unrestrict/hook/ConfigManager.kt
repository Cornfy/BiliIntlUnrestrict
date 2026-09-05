package me.bili.unrestrict.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import me.bili.unrestrict.util.XLog

object ConfigManager {
    private const val TAG = "BiliHook"
    const val ACTION_UPDATE_CONFIG = "me.bili.unrestrict.ACTION_UPDATE_CONFIG"
    const val ACTION_REQUEST_SYNC = "me.bili.unrestrict.ACTION_REQUEST_SYNC"
    private const val PROVIDER_URI = "content://me.bili.unrestrict.provider.config"

    @Volatile
    var bypassTeenagerMode: Boolean = true

    @Volatile
    var enableDebugLogging: Boolean = true

    fun init(context: Context) {
        val sp = context.getSharedPreferences("bili_unrestrict_prefs", Context.MODE_PRIVATE)

        // 1. 首选：通过 ContentProvider 0延迟同步跨进程读取最新配置
        var providerSynced = false
        try {
            val bundle = context.contentResolver.call(
                Uri.parse(PROVIDER_URI),
                "getConfig",
                null,
                null
            )
            if (bundle != null) {
                bypassTeenagerMode = bundle.getBoolean("bypass_teenager_mode", true)
                enableDebugLogging = bundle.getBoolean("enable_debug_logging", true)
                sp.edit()
                    .putBoolean("bypass_teenager_mode", bypassTeenagerMode)
                    .putBoolean("enable_debug_logging", enableDebugLogging)
                    .apply()
                providerSynced = true
                Log.i(TAG, "🔧 [ConfigManager] 同步读取 Provider 配置成功: bypass=$bypassTeenagerMode, log=$enableDebugLogging")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ [ConfigManager] Provider 读取失败，降级读取本地缓存: ${t.message}")
        }

        // 2. 降级备选：从宿主本地 SharedPreferences 缓存读取
        if (!providerSynced) {
            bypassTeenagerMode = sp.getBoolean("bypass_teenager_mode", true)
            enableDebugLogging = sp.getBoolean("enable_debug_logging", true)
            Log.i(TAG, "🔧 [ConfigManager] 读取宿主缓存配置: bypass=$bypassTeenagerMode, log=$enableDebugLogging")
        }

        // 3. 注册广播动态接收配置变动
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_UPDATE_CONFIG) {
                    if (intent.hasExtra("bypass_teenager_mode")) {
                        val v = intent.getBooleanExtra("bypass_teenager_mode", true)
                        bypassTeenagerMode = v
                        sp.edit().putBoolean("bypass_teenager_mode", v).apply()
                        Log.i(TAG, "⚡ [ConfigManager] 青少年模式开关已更新: bypassTeenagerMode=$v")
                    }
                    if (intent.hasExtra("enable_debug_logging")) {
                        val v = intent.getBooleanExtra("enable_debug_logging", true)
                        enableDebugLogging = v
                        sp.edit().putBoolean("enable_debug_logging", v).apply()
                        if (!v) {
                            XLog.clearBuffer()
                        }
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

        // 4. 发送异步握手广播（双保险兜底）
        try {
            val syncIntent = Intent(ACTION_REQUEST_SYNC).apply {
                component = ComponentName("me.bili.unrestrict", "me.bili.unrestrict.ipc.CommentReceiver")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(syncIntent)
        } catch (_: Exception) {}
    }
}
