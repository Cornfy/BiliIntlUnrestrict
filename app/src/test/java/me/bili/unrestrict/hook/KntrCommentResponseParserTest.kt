package me.bili.unrestrict.hook

import org.junit.Assert.*
import org.junit.Test

class KntrCommentResponseParserTest {
    class Content(val message: String)
    // Synthetic fixtures only; never copy account or comment identifiers from user reports.
    class Reply(
        val id: Long = 9_000_000_001,
        val oid: Long = 202,
        val type: Long = 11,
        val root: Long = 0,
        val parent: Long = 0,
        val mid: Long = 505,
        val ctime: Long = 1_700_000_000,
        val content: Content = Content("synthetic comment")
    )
    class Success(@JvmField val a: Reply?, @JvmField val b: Boolean = false)
    private val parser = KntrCommentResponseParser(Success::class.java)

    @Test fun albumRegressionFixture() {
        // b is a UI action flag, not an indication that publishing succeeded.
        assertEquals(PublishedComment(9_000_000_001, 202, 11, 0, 0,
            505, "synthetic comment", 1_700_000_000_000), parser.parse(Success(Reply()), 1))
    }

    @Test fun ignoresSuspensionAndFailureThenAcceptsCompletedReply() {
        assertNull(parser.parse(kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED, 1))
        assertNull(parser.parse(Result.failure<Any>(IllegalStateException()), 1))
        assertNull(parser.parse(null, 1))
        assertNull(parser.parse(Success(null), 1))
        assertNotNull(parser.parse(Success(Reply()), 1))
    }

    @Test fun preservesSubjectAndReplyFieldsAndText() {
        for (type in listOf(1L, 11L, 12L, 17L, 999L)) {
            val result = parser.parse(Success(Reply(type = type, root = 123,
                parent = 456, content = Content("a\n b "))), 1)!!
            assertEquals(type.toInt(), result.type)
            assertEquals(123L, result.root)
            assertEquals(456L, result.parent)
            assertEquals("a\n b ", result.message)
        }
        assertEquals("", parser.parse(Success(Reply(content = Content(""))), 1)!!.message)
    }

    @Test fun rejectsInvalidMetadataAndHandlesTimestampOverflow() {
        for (reply in listOf(Reply(id = 0), Reply(oid = 0), Reply(type = 0),
            Reply(type = Int.MAX_VALUE.toLong() + 1), Reply(root = -1), Reply(parent = -1))) {
            assertNull(parser.parse(Success(reply), 1))
        }
        assertEquals(42L, parser.parse(Success(Reply(ctime = Long.MAX_VALUE)), 42)!!.postTime)
    }
}
