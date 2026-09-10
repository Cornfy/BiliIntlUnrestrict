package me.bili.unrestrict.hook

import io.github.libxposed.api.XposedModule
import me.bili.unrestrict.util.XLog

class FeedRetainHook(private val module: XposedModule) {
    private var isInstalled = false

    fun install(classLoader: ClassLoader) {
        if (isInstalled) return
        try {
            // BiliIntl 6.4.0 推荐流配置类
            val configClass = classLoader.loadClass("LE0.a")

            // i() -> 判断是否清空推荐流列表 (clearFeeds)
            val methodI = configClass.getDeclaredMethod("i")
            module.hook(methodI).intercept { chain ->
                if (ConfigManager.retainFeedHistory) {
                    false // 强制返回 false，不清空旧卡片，追加新内容
                } else {
                    chain.proceed()
                }
            }

            // x() -> 最大卡片保留上限，默认 100。开启时扩大为 200，关闭时保持原样
            val methodX = configClass.getDeclaredMethod("x")
            module.hook(methodX).intercept { chain ->
                if (ConfigManager.retainFeedHistory) {
                    200
                } else {
                    chain.proceed()
                }
            }

            isInstalled = true
            XLog.i("✅ [FeedRetainHook] 首页刷新保留历史拦截器挂载成功")
        } catch (t: Throwable) {
            // 类尚未载入或未命中时不报错，等待后续 classLoader 触发
        }
    }
}
