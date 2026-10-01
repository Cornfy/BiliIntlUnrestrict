package me.bili.unrestrict.hook

import com.bilibili.pegasus.vm.PegasusFeedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保留策略回归测试：读取点筛选 + 降级路径的取值改写。
 *
 * 覆盖的是 JVM 上的纯逻辑；降级路径是否真的会在真机上启用，取决于字段能否反射写入，
 * 属于真机验收范围，不由本测试担保。
 */
class FeedRetainPolicyTest {

    private val hostConfig = PegasusFeedConfig::class.java

    @Test
    fun `无参 int boolean getter 是有效读取点`() {
        assertTrue(FeedRetainPolicy.isReadableConfigAccessor(hostConfig.getDeclaredMethod("isClear")))
        assertTrue(FeedRetainPolicy.isReadableConfigAccessor(hostConfig.getDeclaredMethod("cacheSizeValue")))
        assertTrue(FeedRetainPolicy.isReadableConfigAccessor(hostConfig.getDeclaredMethod("columnValue")))
    }

    @Test
    fun `hashCode 不是读取点`() {
        assertFalse(FeedRetainPolicy.isReadableConfigAccessor(hostConfig.getDeclaredMethod("hashCode")))
    }

    @Test
    fun `有参方法与非数值返回不是读取点`() {
        assertFalse(
            FeedRetainPolicy.isReadableConfigAccessor(
                hostConfig.getMethod("equals", Any::class.java)
            )
        )
        assertFalse(FeedRetainPolicy.isReadableConfigAccessor(hostConfig.getDeclaredMethod("toStringLike")))
    }

    @Test
    fun `降级覆盖 清空开关为 1 时读到 false`() {
        assertEquals(false, FeedRetainPolicy.coerce(true, clearRaw = 1, cacheRaw = 100))
        // 服务端本来就没要求清空时不动，避免误伤其它返回 true 的读取方法
        assertEquals(true, FeedRetainPolicy.coerce(true, clearRaw = 0, cacheRaw = 100))
    }

    @Test
    fun `降级覆盖 上限抬到目标值但不压低服务端更大的值`() {
        assertEquals(200, FeedRetainPolicy.coerce(100, clearRaw = 0, cacheRaw = 100))
        // cacheRaw <= 0 时回落到默认值 100，同样要抬到 200
        assertEquals(200, FeedRetainPolicy.coerce(100, clearRaw = 0, cacheRaw = 0))
        assertEquals(300, FeedRetainPolicy.coerce(300, clearRaw = 0, cacheRaw = 300))
    }

    @Test
    fun `降级覆盖 无关取值原样返回`() {
        assertEquals(2, FeedRetainPolicy.coerce(2, clearRaw = 1, cacheRaw = 100))
        assertEquals("not-a-number", FeedRetainPolicy.coerce("not-a-number", clearRaw = 1, cacheRaw = 100))
    }
}
