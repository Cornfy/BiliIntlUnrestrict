package me.bili.unrestrict

import android.app.Application
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import me.bili.unrestrict.hook.CommentCaptureHook
import me.bili.unrestrict.hook.ConfigManager
import me.bili.unrestrict.hook.TeenagerBypassHook
import me.bili.unrestrict.util.XLog

class MainHook : XposedModule() {

    companion object {
        private const val TARGET_PACKAGE = "com.bilibili.app.in"
    }

    private val teenagerHook = TeenagerBypassHook(this)
    private val commentHook = CommentCaptureHook(this)

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName != TARGET_PACKAGE) return
        XLog.i("🚀 [LibXposed 102] 模块载入 B站包")

        teenagerHook.install(param.defaultClassLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return
        XLog.i("🚀 [LibXposed 102] 完整 Dex 就绪，挂载核心拦截器")

        val classLoader = param.classLoader

        // 尝试提前获取 Application 上下文初始化配置
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null) as? Application
            if (currentApp != null) {
                ConfigManager.init(currentApp)
                XLog.init(currentApp)
            }
        } catch (_: Throwable) {}

        // 🎯 核心：拦截 Application.onCreate
        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            hook(onCreateMethod).intercept { chain ->
                (chain.thisObject as? Application)?.let { app ->
                    ConfigManager.init(app)
                    XLog.init(app)
                    XLog.i("✅ [MainHook] 宿主 Application.onCreate 已执行，当前配置: 青少年中和=${ConfigManager.bypassTeenagerMode}, 运行日志=${ConfigManager.enableDebugLogging}")
                }
                chain.proceed()
            }
            XLog.i("✅ [MainHook] Application.onCreate 监听就绪")
        } catch (t: Throwable) {
            XLog.w("⚠️ Application.onCreate hook 异常: ${t.message}")
        }

        teenagerHook.install(classLoader)
        commentHook.install(classLoader)
    }
}
