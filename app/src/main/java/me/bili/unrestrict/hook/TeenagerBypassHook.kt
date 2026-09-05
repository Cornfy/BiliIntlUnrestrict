package me.bili.unrestrict.hook

import io.github.libxposed.api.XposedModule
import me.bili.unrestrict.util.XLog

class TeenagerBypassHook(private val module: XposedModule) {

    companion object {
        @Volatile
        private var lastLogTime = 0L

        private fun logBypassTrigger(action: String) {
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 4000L) { // 4秒节流防日志刷屏
                lastLogTime = now
                XLog.i("🛡️ [青少年中和] 触发 COPPA 限制拦截: $action，已伪装恢复成人体验")
            }
        }
    }

    fun install(classLoader: ClassLoader) {
        var hookCount = 0

        try {
            val modelStatusClass = classLoader.loadClass("com.bapis.bilibili.app.interfaces.v1.ModelStatus")
            modelStatusClass.getDeclaredMethod("getNumber").let { m ->
                module.hook(m).intercept { chain ->
                    // 🎯 动态检查开关：关了就走原逻辑，开了才替换为 0
                    if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                    logBypassTrigger("ModelStatus.getNumber -> 0")
                    0
                }
                hookCount++
            }
        } catch (_: Throwable) {}

        try {
            val userModelClass = classLoader.loadClass("com.bapis.bilibili.app.interfaces.v1.UserModel")
            userModelClass.getDeclaredMethod("getAge").let { m ->
                module.hook(m).intercept { chain ->
                    if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                    logBypassTrigger("UserModel.getAge -> 22")
                    22
                }
                hookCount++
            }
            userModelClass.getDeclaredMethod("getMustTeen").let { m ->
                module.hook(m).intercept { chain ->
                    if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                    false
                }
                hookCount++
            }
            userModelClass.getDeclaredMethod("getIsForced").let { m ->
                module.hook(m).intercept { chain ->
                    if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                    false
                }
                hookCount++
            }
            userModelClass.getDeclaredMethod("getIsOverseas").let { m ->
                module.hook(m).intercept { chain ->
                    if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                    false
                }
                hookCount++
            }
            userModelClass.getDeclaredMethod("getIsParentControl").let { m ->
                module.hook(m).intercept { chain ->
                    if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                    false
                }
                hookCount++
            }
        } catch (_: Throwable) {}

        try {
            val teenagersKtClass = classLoader.loadClass("com.bilibili.app.comm.restrict.utils.TeenagersModeKt")
            for (method in teenagersKtClass.declaredMethods) {
                if (method.returnType == Boolean::class.javaPrimitiveType) {
                    module.hook(method).intercept { chain ->
                        if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                        logBypassTrigger("TeenagersModeKt.${method.name} -> false")
                        false
                    }
                    hookCount++
                } else if (method.returnType == Int::class.javaPrimitiveType) {
                    module.hook(method).intercept { chain ->
                        if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                        logBypassTrigger("TeenagersModeKt.${method.name} -> 22")
                        22
                    }
                    hookCount++
                }
            }
        } catch (_: Throwable) {}

        try {
            val ageCheckClass = classLoader.loadClass("com.bilibili.teenagersmode.model.TeenagersModeAgeCheck")
            ageCheckClass.getDeclaredMethod("toIntEnum").let { m ->
                module.hook(m).intercept { chain ->
                    if (!ConfigManager.bypassTeenagerMode) return@intercept chain.proceed()
                    4
                }
                hookCount++
            }
        } catch (_: Throwable) {}

        XLog.i("✅ [TeenagerBypass] 动态受控拦截链就绪 (成功挂载 $hookCount 个方法)")
    }
}
