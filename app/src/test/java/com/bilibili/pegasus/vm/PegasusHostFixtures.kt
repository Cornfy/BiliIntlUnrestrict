package com.bilibili.pegasus.vm

import com.google.gson.annotations.SerializedName

/**
 * 测试替身：模拟推荐流配置的结构特征，用于验证定位器与读取点筛选逻辑。
 *
 * 只保留与定位相关的结构特征，不对应任何具体宿主实现：
 * - ViewModel 上有返回状态类的无参方法；
 * - 状态类持有配置类字段；
 * - 配置类的字段带序列化注解名，其中包含「保留上限」与「清空开关」两个语义字段；
 * - 配置类上有无参读取方法，以及 Kotlin 生成的 hashCode。
 */
class PegasusViewModel {

    val feeds: List<String> = emptyList()

    fun getState(): PegasusState = PegasusState(PegasusFeedList(), PegasusFeedConfig())

    fun stateAlias(): PegasusState = getState()
}

class PegasusState(
    val feedList: PegasusFeedList? = null,
    val config: PegasusFeedConfig? = null,
    val flag: Boolean = false
)

class PegasusFeedList(
    @field:SerializedName("items") val items: List<String> = emptyList()
)

class PegasusFeedConfig(
    @field:SerializedName("feed_top_clean") private val clearFlag: Int = 1,
    @field:SerializedName("history_cache_size") private val cacheSize: Int = -1,
    @field:SerializedName("column") private val column: Int = 2
) {

    /** 模拟清空开关的读取。 */
    fun isClear(): Boolean = clearFlag == 1

    /** 模拟保留上限的读取：未下发时回落到默认值。 */
    fun cacheSizeValue(): Int = if (cacheSize > 0) cacheSize else 100

    fun columnValue(): Int = column

    /** 模拟 Kotlin data class 生成的 hashCode：无参返回 int，必须被排除在读取点之外。 */
    override fun hashCode(): Int = clearFlag * 31 + cacheSize

    fun toStringLike(): String = "PegasusFeedConfig"
}

/** 缺少语义字段的干扰状态类，用于验证定位失败时返回 null。 */
class PegasusStateWithoutConfig(
    val feedList: PegasusFeedList? = null
)
