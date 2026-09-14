package me.bili.unrestrict.hook

import android.os.Bundle
import io.github.libxposed.api.XposedModule
import me.bili.unrestrict.util.XLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 6.5.0: ShareTargetTask Runnable -> /x/share/click -> share delegate -> BaseShareParam.
 * Discover obfuscated field/implementation names by type, never by their one-letter names.
 * No clipboard/network hooks. Only the two selected channels receive modified parameters.
 */
class ShareLinkHook(private val module: XposedModule) {
    private val textShareTypes = setOf(
        "com.bilibili.socialize.share.core.shareparam.ShareParamText",
        "com.bilibili.socialize.share.core.shareparam.ShareParamVideo",
        "com.bilibili.socialize.share.core.shareparam.ShareParamWebPage",
    )
    private var installed = false
    private var parameterHookInstalled = false
    private val hooked = mutableSetOf<Method>()
    private val state = ShareLinkState<Bundle>()
    private val videoIds = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }

    @Synchronized
    fun install(loader: ClassLoader) {
        if (installed) return
        try {
            val taskClass = loader.loadClass("com.bilibili.app.comm.supermenu.share.v2.ShareTargetTask")
            val bundleField = taskClass.declaredFields.single { it.type == Bundle::class.java }
                .apply { isAccessible = true }
            val channelFields = taskClass.declaredFields.filter { it.type == String::class.java }
                .onEach { it.isAccessible = true }
            check(channelFields.isNotEmpty()) { "缺少分享渠道字段" }
            val runners = taskClass.declaredFields.map { it.type }.distinct().filter {
                Runnable::class.java.isAssignableFrom(it) &&
                    it.declaredFields.count { field -> field.type == taskClass } == 1
            }
            check(runners.isNotEmpty()) { "分享任务结构不匹配" }

            val base = loader.loadClass("com.bilibili.socialize.share.core.shareparam.BaseShareParam")
            val constructor = base.getDeclaredConstructor(
                String::class.java, String::class.java, String::class.java,
            )
            if (!parameterHookInstalled) {
                module.hook(constructor).intercept { chain ->
                    val content = state.current
                    if (content == null || !ConfigManager.cleanShareLinks ||
                        chain.thisObject?.javaClass?.name !in textShareTypes) chain.proceed()
                    else {
                        // Final construction occurs after both server replacement and tracking injection.
                        val result = chain.proceed(arrayOf(content.title, content.text, content.url))
                        XLog.i("🔗 [ShareLinkHook] 已净化视频分享文本")
                        result
                    }
                }
                parameterHookInstalled = true
            }

            installVideoIdCapture(loader)
            for (runner in runners) {
                val owner = runner.declaredFields.single { it.type == taskClass }
                    .apply { isAccessible = true }
                hookOnce(runner.getDeclaredMethod("run")) { method ->
                    module.hook(method).intercept { chain ->
                        runCatching {
                            val task = owner.get(chain.thisObject)
                            installDelegate(taskClass)
                            val bundle = bundleField.get(task) as? Bundle
                            val channel = channelFields.mapNotNull { it.get(task) as? String }
                                .filter(ShareLinkFormatter::isTargetChannel).singleOrNull()
                            if (bundle != null) {
                                state.capture(bundle, null, null)
                                if (ConfigManager.cleanShareLinks && ShareLinkFormatter.isTargetChannel(channel) &&
                                    bundle.getString("params_type") in setOf("type_video", "type_web", "type_text")) {
                                    val content = ShareLinkFormatter.format(
                                        bundle.getString("params_title"), bundle.getString("params_target_url"),
                                    ) { av -> synchronized(videoIds) { videoIds[av] } }
                                    if (content != null) state.capture(bundle, channel, content)
                                    else XLog.i("🔗 [ShareLinkHook] 未取得完整视频长链，保留原分享")
                                }
                            }
                        }.onFailure { XLog.w("[ShareLinkHook] 分享探测失败: ${it.javaClass.simpleName}") }
                        chain.proceed()
                    }
                }
            }
            installed = true
            XLog.i("✅ [ShareLinkHook] 视频分享净化监听就绪")
        } catch (t: Throwable) {
            XLog.w("[ShareLinkHook] 当前分享结构未适配: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** The helper's static interface field holds the actual implementation selected by the app. */
    private fun installDelegate(taskClass: Class<*>) {
        for (type in taskClass.declaredFields.map { it.type }.distinct()) {
            if (type.isPrimitive || type.name.startsWith("java.") || type.name.startsWith("android.")) continue
            for (field in type.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) || !field.type.isInterface) continue
                if (field.type.methods.none(::isDispatch)) continue
                field.isAccessible = true
                val delegate = field.get(null) ?: continue
                val dispatch = delegate.javaClass.methods.singleOrNull(::isDispatch) ?: continue
                hookOnce(dispatch) { method ->
                    module.hook(method).intercept { chain ->
                        state.within(chain.args[0] as? Bundle, chain.args[1] as? String,
                            ConfigManager.cleanShareLinks) { chain.proceed() }
                    }
                    XLog.i("🔍 [ShareLinkHook] 已定位分享分发器: ${method.declaringClass.name}.${method.name}")
                }
            }
        }
    }

    private fun isDispatch(method: Method) = !Modifier.isStatic(method.modifiers) &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.contentEquals(arrayOf(Bundle::class.java, String::class.java))

    private fun installVideoIdCapture(loader: ClassLoader) {
        runCatching {
            val compat = loader.loadClass("com.bilibili.droid.BVCompat")
            val select = compat.declaredMethods.single {
                Modifier.isStatic(it.modifiers) && it.returnType == String::class.java &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
            }
            hookOnce(select) { method ->
                module.hook(method).intercept { chain ->
                    val av = chain.args[0] as? String
                    val bv = chain.args[1] as? String
                    if (av != null && bv != null && ShareLinkFormatter.isAvid(av) && ShareLinkFormatter.isBvid(bv)) {
                        synchronized(videoIds) { videoIds[av] = bv }
                    }
                    chain.proceed()
                }
            }
        }.onFailure { XLog.w("[ShareLinkHook] BV 对应关系不可用，仅处理原生 BV 长链") }
    }

    @Synchronized
    private fun hookOnce(method: Method, install: (Method) -> Unit) {
        if (method in hooked) return
        install(method)
        hooked.add(method)
    }
}
