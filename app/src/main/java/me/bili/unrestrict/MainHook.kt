package me.bili.unrestrict

import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import me.bili.unrestrict.hook.CommentCaptureHook
import me.bili.unrestrict.hook.ConfigManager
import me.bili.unrestrict.hook.TeenagerBypassHook

class MainHook : XposedModule() {

    companion object {
        private const val TARGET_PACKAGE = "com.bilibili.app.in"
        private const val TAG = "BiliHook"
    }

    private val teenagerHook = TeenagerBypassHook(this)
    private val commentHook = CommentCaptureHook(this)

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName != TARGET_PACKAGE) return
        Log.i(TAG, "🚀 [LibXposed 102] 模块载入 B站包")

        param.defaultClassLoader?.let { classLoader ->
            teenagerHook.install(classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return
        Log.i(TAG, "🚀 [LibXposed 102] 完整 Dex 就绪，挂载核心拦截器")

        val classLoader = param.classLoader ?: return

        // 🎯 核心：Hook Application.onCreate，100% 稳妥拿到 B 站真实的 Context 并初始化配置总线！
        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            hook(onCreateMethod).intercept { chain ->
                val app = chain.thisObject as? Application
                if (app != null) {
                    ConfigManager.init(app)
                }
                chain.proceed()
            }
            Log.i(TAG, "✅ [MainHook] Application.onCreate 监听就绪")
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ Application.onCreate hook 异常: ${t.message}")
        }

        // 挂载核心业务 Hook
        teenagerHook.install(classLoader)
        commentHook.install(classLoader)
    }
}
