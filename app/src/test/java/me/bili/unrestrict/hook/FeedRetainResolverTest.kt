package me.bili.unrestrict.hook

import com.bilibili.pegasus.vm.PegasusFeedConfig
import com.bilibili.pegasus.vm.PegasusViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位器回归测试：验证不依赖混淆类名/方法名时能否找到推荐流配置的两个语义字段。
 *
 * 这些测试只覆盖 JVM 上的反射定位逻辑，不等于 LSPosed 真机挂载成功。
 */
class FeedRetainResolverTest {

    private val classLoader: ClassLoader = PegasusViewModel::class.java.classLoader!!

    @Test
    fun `按语义名定位到配置类`() {
        val config = FeedRetainResolver.resolve(classLoader)

        assertNotNull(config)
        assertEquals(PegasusFeedConfig::class.java, config!!.configClass)
    }

    @Test
    fun `定位到的字段与服务端契约名一致`() {
        val config = FeedRetainResolver.resolve(classLoader)!!

        assertEquals("history_cache_size", serializedNameOf(config.cacheSizeField))
        assertEquals("feed_top_clean", serializedNameOf(config.clearField))
    }

    @Test
    fun `定位到的字段可读写且取值正确`() {
        val config = FeedRetainResolver.resolve(classLoader)!!
        val instance = PegasusFeedConfig(clearFlag = 1, cacheSize = -1)

        assertEquals(-1, config.cacheSizeField.getInt(instance))
        assertEquals(1, config.clearField.getInt(instance))

        config.cacheSizeField.setInt(instance, 200)
        config.clearField.setInt(instance, 0)

        assertEquals(0, config.clearField.getInt(instance))
        // 读取方法取的是同一字段，改写后应立即生效
        assertEquals(false, instance.isClear())
        assertEquals(200, instance.cacheSizeValue())
    }

    @Test
    fun `改写清空开关后宿主判定为不清空`() {
        val config = FeedRetainResolver.resolve(classLoader)!!
        val instance = PegasusFeedConfig(clearFlag = 1, cacheSize = 100)

        assertTrue(instance.isClear())

        config.clearField.setInt(instance, 0)

        assertEquals(false, instance.isClear())
    }

    private fun serializedNameOf(field: java.lang.reflect.Field): String? {
        for (annotation in field.declaredAnnotations) {
            val javaAnnotation = annotation as? java.lang.annotation.Annotation ?: continue
            val value = runCatching {
                javaAnnotation.annotationType().getMethod("value").invoke(javaAnnotation) as? String
            }.getOrNull()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }
}
