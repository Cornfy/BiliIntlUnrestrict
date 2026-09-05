package me.bili.unrestrict.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "me.bili.unrestrict.provider.config"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_GET_CONFIG = "getConfig"
        const val METHOD_UPDATE_CONFIG = "updateConfig"

        const val KEY_BYPASS_TEENAGER = "bypass_teenager_mode"
        const val KEY_ENABLE_DEBUG_LOG = "enable_debug_logging"
        const val KEY_BILI_COOKIE = "bili_cookie"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val sp = ctx.getSharedPreferences("module_config", Context.MODE_PRIVATE)

        return when (method) {
            METHOD_GET_CONFIG -> {
                Bundle().apply {
                    putBoolean(KEY_BYPASS_TEENAGER, sp.getBoolean(KEY_BYPASS_TEENAGER, true))
                    putBoolean(KEY_ENABLE_DEBUG_LOG, sp.getBoolean(KEY_ENABLE_DEBUG_LOG, true))
                    putString(KEY_BILI_COOKIE, sp.getString(KEY_BILI_COOKIE, "").orEmpty())
                }
            }
            METHOD_UPDATE_CONFIG -> {
                extras?.let { bundle ->
                    val editor = sp.edit()
                    if (bundle.containsKey(KEY_BYPASS_TEENAGER)) {
                        editor.putBoolean(KEY_BYPASS_TEENAGER, bundle.getBoolean(KEY_BYPASS_TEENAGER))
                    }
                    if (bundle.containsKey(KEY_ENABLE_DEBUG_LOG)) {
                        editor.putBoolean(KEY_ENABLE_DEBUG_LOG, bundle.getBoolean(KEY_ENABLE_DEBUG_LOG))
                    }
                    if (bundle.containsKey(KEY_BILI_COOKIE)) {
                        editor.putString(KEY_BILI_COOKIE, bundle.getString(KEY_BILI_COOKIE))
                    }
                    editor.apply()
                }
                Bundle().apply { putBoolean("success", true) }
            }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
