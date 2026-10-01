package me.bili.unrestrict.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 推荐流保留策略的纯逻辑，抽出来是为了可单测（Hook 本身依赖 XposedModule，无法在 JVM 上直接测）。
 *
 * 两条路径共用这些常量与判定：
 * - 主路径：在宿主 getter 执行前反射改写配置字段；
 * - 降级路径：字段写不进去（宿主字段是 private final）时，改为改写 getter 的返回值。
 */
internal object FeedRetainPolicy {

    const val TARGET_CACHE_SIZE = 200

    /** 保留上限未下发时的回落值。 */
    const val HOST_DEFAULT_CACHE_SIZE = 100

    /**
     * Object 的覆写方法名。它们不会被混淆（因为是覆写），
     * 但 hashCode() 恰好是「无参返回 int」，会被误当成配置读取点挂上钩子——热路径，必须排除。
     */
    private val OBJECT_METHOD_NAMES = setOf("hashCode", "equals", "toString", "clone", "finalize")

    /** 判断某方法是否可以当作「配置读取点」挂载钩子。 */
    fun isReadableConfigAccessor(method: Method): Boolean {
        if (method.parameterTypes.isNotEmpty()) return false
        if (Modifier.isStatic(method.modifiers)) return false
        if (method.isSynthetic || method.isBridge) return false
        if (method.name in OBJECT_METHOD_NAMES) return false
        return isIntOrBoolean(method.returnType)
    }

    /** 宿主侧的有效上限值：服务端下发 >0 用下发值，否则回落到宿主默认值。 */
    fun effectiveCacheSize(raw: Int): Int = if (raw > 0) raw else HOST_DEFAULT_CACHE_SIZE

    /**
     * 降级路径用：把宿主读到的值改写成我们想让它读到的值。
     *
     * 判定不依赖任何混淆名，只用字段原值与返回值的关系：
     * - boolean：清空开关为 1 时读到 true，翻成 false；
     * - int：返回值等于有效上限值且低于目标值时抬到目标值。
     *
     * 代价：若配置类里存在取值恰好相同的无关字段（例如另一个值也为 100 的 int 配置），
     * 会被一并改写。因此这条路径只在主路径（写字段）确认不可用时启用，且会打警告日志。
     */
    fun coerce(result: Any?, clearRaw: Int, cacheRaw: Int): Any? = when (result) {
        is Boolean -> if (result && clearRaw == 1) false else result
        is Int -> {
            val effective = effectiveCacheSize(cacheRaw)
            if (result == effective && effective < TARGET_CACHE_SIZE) TARGET_CACHE_SIZE else result
        }
        else -> result
    }

    private fun isIntOrBoolean(type: Class<*>): Boolean =
        type == java.lang.Integer.TYPE || type == java.lang.Boolean.TYPE ||
            type == java.lang.Integer::class.java || type == java.lang.Boolean::class.java
}
