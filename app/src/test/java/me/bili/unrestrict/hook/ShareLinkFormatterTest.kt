package me.bili.unrestrict.hook

import org.junit.Assert.*
import org.junit.Test

class ShareLinkFormatterTest {
    private val video = "https://www.bilibili.com/video/BV1xx411c7mD"

    @Test fun formatsTitleAndRemovesOnlyKnownTracking() {
        val result = ShareLinkFormatter.format("测试标题", "$video?buvid=abc&share_session_id=123&ts=123456")!!
        assertEquals("【测试标题】\n$video", result.text)
    }

    @Test fun preservesPageTimestampAndUnknownEncodedParameters() {
        val query = "p=2&t=90&future=a%2Fb%26c&future=second"
        val result = ShareLinkFormatter.format("【分 P】", "$video?$query&share_source=copy#part%202")!!
        assertEquals("【分 P】\n$video?$query#part%202", result.text)
    }

    @Test fun preservesFirstPageAndUnknownFromParameter() {
        assertEquals("$video?p=1&from=special", ShareLinkFormatter.format("标题", "$video?p=1&from=special")!!.url)
    }

    @Test fun rejectsOtherContentAndUntrustedOrAmbiguousUrls() {
        listOf(
            "https://b23.tv/abcdef", "https://www.bilibili.com/read/cv123",
            "https://www.bilibili.com/bangumi/play/ep123", "$video/extra",
            "https://www.bilibili.com.evil.example/video/BV1xx411c7mD",
            "https://user@www.bilibili.com/video/BV1xx411c7mD", "$video bad",
            "https://www.bilibili.com:123/video/BV1xx411c7mD",
        ).forEach { assertNull(it, ShareLinkFormatter.format("标题", it)) }
        assertNull(ShareLinkFormatter.format(" ", video))
        assertNull(ShareLinkFormatter.format("标题", null))
    }

    @Test fun convertsOnlyWithExactAppProvidedAvMapping() {
        val avUrl = "https://www.bilibili.com/video/av170001?p=3"
        assertNull(ShareLinkFormatter.format("标题", avUrl))
        val result = ShareLinkFormatter.format("标题", avUrl) {
            if (it == "av170001") "BV1xx411c7mD" else null
        }
        assertEquals("$video?p=3", result!!.url)
        assertNull(ShareLinkFormatter.format("标题", avUrl) { "invalid" })
    }

    @Test fun decodesParameterNamesButPreservesValuesAndIsIdempotent() {
        val result = ShareLinkFormatter.format("【标题】", "$video?%62uvid=abc&p=2&future=a+b")!!
        assertEquals("$video?p=2&future=a+b", result.url)
        assertEquals(result, ShareLinkFormatter.format(result.title, result.url))
    }

    @Test fun targetsOnlyCopyAndMore() {
        assertTrue(ShareLinkFormatter.isTargetChannel("COPY"))
        assertTrue(ShareLinkFormatter.isTargetChannel("GENERIC"))
        listOf(null, "biliDynamic", "biliIm", "WEIXIN", "QQ", "MARK_POINT", "PIC").forEach {
            assertFalse(ShareLinkFormatter.isTargetChannel(it))
        }
    }
}
