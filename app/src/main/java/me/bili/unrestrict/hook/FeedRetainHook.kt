package me.bili.unrestrict.hook

import io.github.libxposed.api.XposedModule
import me.bili.unrestrict.util.XLog

class FeedRetainHook(private val module: XposedModule) {
    private var isInstalled = false

    fun install(classLoader: ClassLoader) {
        if (isInstalled) return
        try {
            val configClass = findConfigClass(classLoader) ?: return

            // i() -> 判断是否清空推荐流列表 (clearFeeds)
            val methodI = configClass.getDeclaredMethod("i")
            module.hook(methodI).intercept { chain ->
                if (ConfigManager.retainFeedHistory) {
                    false // 强制返回 false，保留旧卡片并置顶新卡片
                } else {
                    chain.proceed()
                }
            }

            // x() -> 最大卡片保留上限，默认 100。开启时扩大为 200
            val methodX = configClass.getDeclaredMethod("x")
            module.hook(methodX).intercept { chain ->
                if (ConfigManager.retainFeedHistory) {
                    200
                } else {
                    chain.proceed()
                }
            }

            isInstalled = true
            XLog.i("✅ [FeedRetainHook] 首页刷新保留历史拦截器挂载成功: ${configClass.name}")
        } catch (t: Throwable) {
            // 类尚未载入或未命中时不报错，等待后续流程重试
        }
    }

    /**
     * 优先通过 PegasusViewModel.getState() 动态探测配置类（适应未来版本混淆变化），
     * 探测失败则回退到已知版本静态表。
     */
    private fun findConfigClass(classLoader: ClassLoader): Class<*>? {
        // 1. 动态自适应探测
        runCatching {
            val vmClass = classLoader.loadClass("com.bilibili.pegasus.vm.PegasusViewModel")
            val getStateMethod = vmClass.declaredMethods.firstOrNull { it.name == "getState" }
            val stateClass = getStateMethod?.returnType

            if (stateClass != null) {
                for (field in stateClass.declaredFields) {
                    val candidate = field.type
                    val hasMethodI = candidate.declaredMethods.any {
                        it.name == "i" && it.returnType == java.lang.Boolean.TYPE && it.parameterTypes.isEmpty()
                    }
                    val hasMethodX = candidate.declaredMethods.any {
                        it.name == "x" && it.returnType == java.lang.Integer.TYPE && it.parameterTypes.isEmpty()
                    }
                    if (hasMethodI && hasMethodX) {
                        XLog.i("🔍 [FeedRetainHook] 动态自适应定位到推荐流配置类: ${candidate.name}")
                        return candidate
                    }
                }
            }
        }

        // 2. 已知版本回退表
        val knownClasses = listOf(
            "EE0.a", // 6.5.0
            "LE0.a"  // 6.4.0
        )
        for (className in knownClasses) {
            runCatching {
                return classLoader.loadClass(className)
            }
        }

        return null
    }
}
