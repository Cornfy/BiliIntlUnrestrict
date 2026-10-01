package com.google.gson.annotations

/**
 * 测试替身：仅用于单元测试里复刻宿主配置类的 Gson 注解。
 * 包路径与真实 Gson 注解一致，模块按注解类型名字符串识别，因此不引入 gson 依赖。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class SerializedName(val value: String = "", val alternate: Array<String> = [])
