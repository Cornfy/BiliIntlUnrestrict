package me.bili.unrestrict.hook

import android.util.Log
import io.github.libxposed.api.XposedModule

class TeenagerBypassHook(private val module: XposedModule) {

    companion object {
        private const val TAG = "BiliHook"
    }

    fun install(classLoader: ClassLoader) {
        try {
            val modelStatusClass = classLoader.loadClass("com.bapis.bilibili.app.interfaces.v1.ModelStatus")
            modelStatusClass.getDeclaredMethod("getNumber").let { m ->
                module.hook(m).intercept { chain ->
                    // 🎯 动态检查开关：关了就走原逻辑，开了才替换为 0
                    if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                    0
                }
            }
        } catch (_: Throwable) {}

        try {
            val userModelClass = classLoader.loadClass("com.bapis.bilibili.app.interfaces.v1.UserModel")
            userModelClass.getDeclaredMethod("getAge").let { m ->
                module.hook(m).intercept { chain -> if (!ConfigManager.bypassTeenagerMode) chain.proceed() else 22 }
            }
            userModelClass.getDeclaredMethod("getMustTeen").let { m ->
                module.hook(m).intercept { chain -> if (!ConfigManager.bypassTeenagerMode) chain.proceed() else false }
            }
            userModelClass.getDeclaredMethod("getIsForced").let { m ->
                module.hook(m).intercept { chain -> if (!ConfigManager.bypassTeenagerMode) chain.proceed() else false }
            }
            userModelClass.getDeclaredMethod("getIsOverseas").let { m ->
                module.hook(m).intercept { chain -> if (!ConfigManager.bypassTeenagerMode) chain.proceed() else false }
            }
            userModelClass.getDeclaredMethod("getIsParentControl").let { m ->
                module.hook(m).intercept { chain -> if (!ConfigManager.bypassTeenagerMode) chain.proceed() else false }
            }
        } catch (_: Throwable) {}

        try {
            val teenagersKtClass = classLoader.loadClass("com.bilibili.app.comm.restrict.utils.TeenagersModeKt")
            for (method in teenagersKtClass.declaredMethods) {
                if (method.returnType == Boolean::class.javaPrimitiveType) {
                    module.hook(method).intercept { chain -> if (!ConfigManager.bypassTeenagerMode) chain.proceed() else false }
                } else if (method.returnType == Int::class.javaPrimitiveType) {
                    module.hook(method).intercept { chain -> if (!ConfigManager.bypassTeenagerMode) chain.proceed() else 22 }
                }
            }
        } catch (_: Throwable) {}

        try {
            val ageCheckClass = classLoader.loadClass("com.bilibili.teenagersmode.model.TeenagersModeAgeCheck")
            ageCheckClass.getDeclaredMethod("toIntEnum").let { m ->
                module.hook(m).intercept { chain -> if (!ConfigManager.bypassTeenagerMode) chain.proceed() else 4 }
            }
        } catch (_: Throwable) {}

        Log.i(TAG, "✅ [TeenagerBypass] 动态受控拦截链就绪")
    }
}
