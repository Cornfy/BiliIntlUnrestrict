package me.bili.unrestrict.hook

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 推荐流配置类的结构化定位器。
 *
 * 只依赖「具名锚点」与「服务端契约字段名」两类稳定信息，不硬编码任何混淆类名或方法名：
 * - 具名锚点：com.bilibili.pegasus.vm.PegasusViewModel（业务包名）
 * - 契约名：history_cache_size、feed_top_clean（Gson @SerializedName，随接口下发）
 *
 * 返回的字段用于改写宿主的推荐流保留策略，见 FeedRetainHook。
 */
internal object FeedRetainResolver {

    const val VM_CLASS = "com.bilibili.pegasus.vm.PegasusViewModel"
    const val CACHE_SIZE_NAME = "history_cache_size"
    const val CLEAR_NAME = "feed_top_clean"

    private const val ANN_GSON = "com.google.gson.annotations.SerializedName"
    private const val ANN_MOSHI = "com.squareup.moshi.Json"

    private val SKIP_PREFIXES = arrayOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "dalvik."
    )

    class FeedConfig(
        val configClass: Class<*>,
        val cacheSizeField: Field,
        val clearField: Field
    )

    fun resolve(classLoader: ClassLoader): FeedConfig? {
        val vmClass = runCatching { Class.forName(VM_CLASS, false, classLoader) }.getOrNull()
            ?: return null

        for (stateClass in stateCandidates(vmClass)) {
            val configClass = findConfigClass(stateClass) ?: continue
            val cacheField = findNamedField(configClass, CACHE_SIZE_NAME) ?: continue
            val clearField = findNamedField(configClass, CLEAR_NAME) ?: continue
            cacheField.isAccessible = true
            clearField.isAccessible = true
            return FeedConfig(configClass, cacheField, clearField)
        }
        return null
    }

    /**
     * 状态类候选：ViewModel 上所有非静态、非合成的无参方法的返回类型。
     * 不假设 getState() 这个具体名字存在。
     */
    private fun stateCandidates(vmClass: Class<*>): List<Class<*>> {
        val result = LinkedHashSet<Class<*>>()
        for (method in vmClass.declaredMethods) {
            if (method.parameterTypes.isNotEmpty()) continue
            if (Modifier.isStatic(method.modifiers) || method.isSynthetic) continue
            val returnType = method.returnType
            if (returnType == Void.TYPE || returnType.isPrimitive || returnType.isArray) continue
            if (isSkippedType(returnType)) continue
            result.add(returnType)
        }
        return result.toList()
    }

    /**
     * 在状态类的字段类型中找配置类（必要时向下再探一层）。
     * 判定依据：该类型带有名为 history_cache_size 的 int 字段。
     */
    private fun findConfigClass(stateClass: Class<*>): Class<*>? {
        val direct = stateClass.declaredFields.map { it.type }.filter { isCandidateType(it, stateClass) }
        direct.firstOrNull { hasNamedField(it, CACHE_SIZE_NAME) }?.let { return it }

        for (candidate in direct) {
            val nested = candidate.declaredFields.map { it.type }.filter { isCandidateType(it, stateClass) }
            nested.firstOrNull { hasNamedField(it, CACHE_SIZE_NAME) }?.let { return it }
        }
        return null
    }

    private fun isCandidateType(type: Class<*>, owner: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray) return false
        if (type === owner) return false
        return !isSkippedType(type)
    }

    private fun isSkippedType(type: Class<*>): Boolean {
        val name = type.name
        return SKIP_PREFIXES.any { name.startsWith(it) }
    }

    private fun hasNamedField(type: Class<*>, serializedName: String): Boolean {
        return findNamedField(type, serializedName) != null
    }

    private fun findNamedField(type: Class<*>, serializedName: String): Field? {
        for (field in type.declaredFields) {
            if (field.type != java.lang.Integer.TYPE && field.type != java.lang.Integer::class.java) continue
            if (serializedName(field) == serializedName) return field
        }
        return null
    }

    private fun serializedName(field: Field): String? {
        for (annotation in field.declaredAnnotations) {
            val javaAnnotation = annotation as? java.lang.annotation.Annotation ?: continue
            val annotationType = javaAnnotation.annotationType().name
            if (annotationType != ANN_GSON && annotationType != ANN_MOSHI) continue
            for (accessor in arrayOf("value", "name")) {
                val value = runCatching {
                    javaAnnotation.annotationType().getMethod(accessor).invoke(javaAnnotation) as? String
                }.getOrNull()
                if (!value.isNullOrEmpty()) return value
            }
        }
        return null
    }
}
