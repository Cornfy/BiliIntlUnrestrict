package me.bili.unrestrict.hook

import io.github.libxposed.api.XposedModule
import me.bili.unrestrict.util.XLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * 首页刷新保留历史。
 *
 * 宿主在刷新时按「清空开关」决定是否丢弃旧列表，再按「保留上限」截断剩余条目。
 * 本 Hook 只调整这两个配置值，不干涉列表的合并与渲染。
 *
 * 本 Hook 不硬编码任何混淆方法名：
 * 1. 用 FeedRetainResolver 按语义名定位配置类与两个配置字段；
 * 2. 拦截配置类上的无参 int/boolean 读取方法（排除 hashCode 等 Object 覆写方法）；
 * 3. 主路径：在宿主读到之前把字段改写成「不清空 + 上限 200」；
 * 4. 降级路径：宿主字段是 private final，若运行时拒绝反射写入（回读校验失败），
 *    改为在 getter 返回后直接改写返回值，不再触碰字段。
 */
class FeedRetainHook(private val module: XposedModule) {

    companion object {
        private const val TARGET_CACHE_SIZE = FeedRetainPolicy.TARGET_CACHE_SIZE

        @Volatile
        private var lastLogTime = 0L

        private fun logThrottled(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastLogTime <= 4000L) return
            lastLogTime = now
            XLog.i(message)
        }
    }

    @Volatile
    private var config: FeedRetainResolver.FeedConfig? = null

    @Volatile
    private var installed = false

    @Volatile
    private var writeFailed = false

    /**
     * 弱引用 + 对象身份：宿主配置类多是 Kotlin data class，equals/hashCode 依赖字段值，
     * 改写字段后哈希会变，直接当 HashMap 的键会丢失记录，因此按对象身份索引。
     */
    private class IdentityRef(target: Any) {
        private val ref = WeakReference(target)
        private val hash = System.identityHashCode(target)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val that = other as? IdentityRef ?: return false
            val mine = ref.get() ?: return false
            return mine === that.ref.get()
        }
    }

    private val originalValues = Collections.synchronizedMap(WeakHashMap<IdentityRef, IntArray>())

    fun install(classLoader: ClassLoader) {
        if (installed) return

        val target = config ?: FeedRetainResolver.resolve(classLoader)
        if (target == null) {
            logThrottled("⚠️ [FeedRetainHook] 尚未定位到推荐流配置类，等待宿主类载入后重试")
            return
        }
        config = target

        var hooked = 0
        for (method in target.configClass.declaredMethods) {
            if (!FeedRetainPolicy.isReadableConfigAccessor(method)) continue

            runCatching {
                module.hook(method).intercept { chain ->
                    val instance = chain.thisObject
                    applyPolicy(instance)
                    val result = chain.proceed()
                    if (writeFailed && ConfigManager.retainFeedHistory) {
                        coerceResult(result, instance)
                    } else {
                        result
                    }
                }
                hooked++
            }
        }

        if (hooked == 0) {
            logThrottled("⚠️ [FeedRetainHook] 配置类 ${target.configClass.name} 未发现可挂载的读取方法")
            return
        }

        installed = true
        XLog.i(
            "✅ [FeedRetainHook] 已挂载 $hooked 个配置读取点: ${target.configClass.name} " +
                "(上限字段=${target.cacheSizeField.name}[${FeedRetainResolver.CACHE_SIZE_NAME}], " +
                "清空字段=${target.clearField.name}[${FeedRetainResolver.CLEAR_NAME}])"
        )
    }

    private fun applyPolicy(instance: Any?) {
        val target = config ?: return
        if (instance == null || !target.configClass.isInstance(instance)) return
        if (writeFailed) return

        try {
            if (!ConfigManager.retainFeedHistory) {
                restore(instance, target)
                return
            }

            val cacheNow = readInt(target.cacheSizeField, instance) ?: return
            val clearNow = readInt(target.clearField, instance) ?: return

            val key = IdentityRef(instance)
            val isFirstPatch = !originalValues.containsKey(key)
            if (isFirstPatch) {
                originalValues[key] = intArrayOf(cacheNow, clearNow)
            }

            if (cacheNow < TARGET_CACHE_SIZE) {
                writeInt(target.cacheSizeField, instance, TARGET_CACHE_SIZE)
            }
            if (clearNow != 0) {
                writeInt(target.clearField, instance, 0)
            }

            // 首次改写后回读校验：宿主配置字段是 final，若运行时拒绝写入则显式放弃，不留静默失效
            if (isFirstPatch) {
                val cacheAfter = readInt(target.cacheSizeField, instance)
                val clearAfter = readInt(target.clearField, instance)
                if (cacheAfter == null || cacheAfter < TARGET_CACHE_SIZE || clearAfter != 0) {
                    writeFailed = true
                    XLog.w(
                        "⚠️ [FeedRetainHook] 配置字段写入未生效(上限=$cacheAfter, 清空=$clearAfter)，" +
                            "切换为降级模式：改写读取值而非字段"
                    )
                    return
                }
            }

            logThrottled("📺 [FeedRetainHook] 保留浏览历史: 上限 $cacheNow -> $TARGET_CACHE_SIZE, 清空标志 $clearNow -> 0")
        } catch (t: Throwable) {
            writeFailed = true
            XLog.w("⚠️ [FeedRetainHook] 写入推荐流配置失败(${t.javaClass.simpleName}: ${t.message})，本进程放弃该功能")
        }
    }

    /**
     * 降级路径：字段写不进去时，直接改写读到的值。
     *
     * 两个配置字段均只经由读取方法对外暴露，因此改写返回值与改写字段等价。
     */
    private fun coerceResult(result: Any?, instance: Any?): Any? {
        val target = config ?: return result
        if (instance == null || !target.configClass.isInstance(instance)) return result
        val clearRaw = readInt(target.clearField, instance) ?: return result
        val cacheRaw = readInt(target.cacheSizeField, instance) ?: return result

        val coerced = FeedRetainPolicy.coerce(result, clearRaw, cacheRaw)
        if (coerced != result) {
            logThrottled("📺 [FeedRetainHook] 降级模式: 覆盖读取值 $result -> $coerced")
        }
        return coerced
    }

    /**
     * 开关关闭时把字段还原为首次改写前的值，避免本次进程残留改写结果。
     */
    private fun restore(instance: Any, target: FeedRetainResolver.FeedConfig) {
        val snapshot = originalValues[IdentityRef(instance)] ?: return
        val cacheNow = readInt(target.cacheSizeField, instance) ?: return
        val clearNow = readInt(target.clearField, instance) ?: return
        if (cacheNow != snapshot[0]) writeInt(target.cacheSizeField, instance, snapshot[0])
        if (clearNow != snapshot[1]) writeInt(target.clearField, instance, snapshot[1])
    }

    private fun readInt(field: Field, instance: Any): Int? = when (field.type) {
        java.lang.Integer.TYPE -> field.getInt(instance)
        java.lang.Integer::class.java -> (field.get(instance) as? Number)?.toInt()
        else -> null
    }

    private fun writeInt(field: Field, instance: Any, value: Int) = when (field.type) {
        java.lang.Integer.TYPE -> field.setInt(instance, value)
        else -> field.set(instance, value)
    }
}
