package me.bili.unrestrict.data.model

import me.bili.unrestrict.data.db.CommentFraudRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFraudSearchTest {

    private fun record(
        rpid: Long,
        oid: Long = 100L,
        message: String = "",
        sourceId: String? = null
    ) = CommentFraudRecord(
        rpid = rpid,
        oid = oid,
        message = message,
        source_id = sourceId
    )

    private val records = listOf(
        record(rpid = 111L, oid = 900L, message = "这个视频拍得太好了", sourceId = "BV1xx411c7mD"),
        record(rpid = 222L, oid = 800L, message = "Good Shot!", sourceId = "BV1yy411c7mE"),
        record(rpid = 333L, oid = 700L, message = "第一条评论", sourceId = null)
    )

    @Test
    fun blankKeywordKeepsEveryRecord() {
        assertSame(records, CommentFraudSearch.filter(records, ""))
        assertSame(records, CommentFraudSearch.filter(records, "   "))
    }

    @Test
    fun keywordMatchesMessageIgnoringCase() {
        val result = CommentFraudSearch.filter(records, "太好")
        assertEquals(listOf(111L), result.map { it.rpid })

        assertEquals(listOf(111L), CommentFraudSearch.filter(records, "拍得").map { it.rpid })
        assertEquals(listOf(222L), CommentFraudSearch.filter(records, "good").map { it.rpid })
        assertEquals(listOf(222L), CommentFraudSearch.filter(records, "SHOT").map { it.rpid })
    }

    @Test
    fun keywordAlsoMatchesSourceId() {
        val result = CommentFraudSearch.filter(records, "1yy411")
        assertEquals(listOf(222L), result.map { it.rpid })
    }

    @Test
    fun numericKeywordHitsCommentIdAndAreaId() {
        assertEquals(listOf(222L), CommentFraudSearch.filter(records, "222").map { it.rpid })
        assertEquals(listOf(111L), CommentFraudSearch.filter(records, "900").map { it.rpid })
        assertTrue(CommentFraudSearch.filter(records, "123456").isEmpty())
    }

    @Test
    fun partialNumberStillFallsBackToTextMatch() {
        val dataset = listOf(record(rpid = 5L, message = "1234567890123456 号选手很强"))
        assertEquals(listOf(5L), CommentFraudSearch.filter(dataset, "3456").map { it.rpid })
    }

    @Test
    fun noMatchReturnsEmptyList() {
        assertTrue(CommentFraudSearch.filter(records, "不存在的关键词").isEmpty())
    }

    @Test
    fun highlightRangesFindsRepeatedCaseInsensitiveHits() {
        val ranges = CommentFraudSearch.highlightRanges("aXbXcX", "x")
        assertEquals(listOf(1..1, 3..3, 5..5), ranges.map { it.first..it.last })
    }

    @Test
    fun highlightRangesHandlesEdgeInputs() {
        assertTrue(CommentFraudSearch.highlightRanges("anything", "").isEmpty())
        assertTrue(CommentFraudSearch.highlightRanges("anything", "  ").isEmpty())
        assertTrue(CommentFraudSearch.highlightRanges("", "x").isEmpty())
        assertTrue(CommentFraudSearch.highlightRanges("ab", "abc").isEmpty())
        assertTrue(CommentFraudSearch.highlightRanges("abc", "xyz").isEmpty())
    }

    @Test
    fun overlappingPatternDoesNotProduceCrossedRanges() {
        val ranges = CommentFraudSearch.highlightRanges("aaaa", "aa")
        assertEquals(listOf(0..1, 2..3), ranges.map { it.first..it.last })
    }

    @Test
    fun parseKeepsTrimmedKeywordAndNumericForm() {
        val trimmed = CommentFraudSearch.parse("  777  ")
        assertEquals("777", trimmed.keyword)
        assertEquals(777L, trimmed.numeric)

        val text = CommentFraudSearch.parse(" hello ")
        assertEquals("hello", text.keyword)
        assertEquals(null, text.numeric)
        assertFalse(text.isEmpty)
    }
}
