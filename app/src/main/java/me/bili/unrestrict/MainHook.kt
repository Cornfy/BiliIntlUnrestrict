package me.bili.unrestrict

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import me.bili.unrestrict.hook.CommentCaptureHook
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

        param.classLoader?.let { classLoader ->
            teenagerHook.install(classLoader)
            commentHook.install(classLoader)
        }
    }
}
