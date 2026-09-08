package me.bili.unrestrict.hook

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CommentCaptureStateTest {
    // Public host-shaped objects exercise reflection, including inherited BaseResponse.code.
    class RawResponse(private val successful: Boolean) {
        fun p() = successful
    }
    open class BaseResponse(@JvmField var code: Int = 0)
    class Body(@JvmField var data: Any? = AddResult()) : BaseResponse()
    class Response(@JvmField var b: Body? = Body(), successful: Boolean = true) {
        @JvmField val a = RawResponse(successful)
    }
    class AddResult(@JvmField var reply: Reply? = Reply())
    class Content(@JvmField var mMsg: String = "服务器正文\n第二行")
    class Reply {
        @JvmField var mRpId = 101L
        @JvmField var mOid = 202L
        @JvmField var mType = 17
        @JvmField var mRootId = 303L
        @JvmField var mParentId = 404L
        @JvmField var mMid = 505L
        @JvmField var mCtime = 1_700_000_000L
        @JvmField var mContent: Content? = Content()
    }

    private val parser = LegacyCommentResponseParser(AddResult::class.java)
    private val request = CommentPostRequest(20, 1, 30, 40, "请求正文\n第二行", "", "Ai.a.o")

    @Test fun successfulResponseUsesServerFieldsAndPreservesMultilineText() {
        assertEquals(PublishedComment(101, 202, 17, 303, 404, 505,
            "服务器正文\n第二行", 1_700_000_000_000), parser.parse(Response(), request, 1000))
    }

    @Test fun failedHttpBusinessCaptchaAndEmptyRepliesNeverBecomeRecords() {
        assertNull(parser.parse(null, request, 1000))
        assertNull(parser.parse(Response(successful = false), request, 1000))
        for (code in listOf(-352, 12015, -101)) {
            val response = Response()
            response.b!!.code = code
            assertNull(parser.parse(response, request, 1000))
        }
        assertNull(parser.parse(Response(b = null), request, 1000))
        assertNull(parser.parse(Response(Body(null)), request, 1000))
        assertNull(parser.parse(Response(Body(Any())), request, 1000))
        assertNull(parser.parse(Response(Body(AddResult(null))), request, 1000))
        for (id in listOf(0L, -1L)) {
            val reply = Reply().apply { mRpId = id }
            assertNull(parser.parse(Response(Body(AddResult(reply))), request, 1000))
        }
    }

    @Test fun missingResponseMetadataFallsBackToMatchingRequestForAnyBusinessType() {
        for (type in listOf(1, 11, 12, 17, 999)) {
            val reply = Reply().apply {
                mOid = 0; mType = 0; mRootId = 0; mParentId = 0; mCtime = 0; mContent = null
            }
            val parsed = parser.parse(Response(Body(AddResult(reply))), request.copy(type = type), 9000)!!
            assertEquals(20L, parsed.oid)
            assertEquals(type, parsed.type)
            assertEquals(30L, parsed.root)
            assertEquals(40L, parsed.parent)
            assertEquals(request.message, parsed.message)
            assertEquals(9000L, parsed.postTime)
        }
    }

    @Test fun rootCommentStaysRootAndInvalidSubjectIsRejected() {
        val reply = Reply().apply { mOid = 0; mType = 0; mRootId = 0; mParentId = 0 }
        val response = Response(Body(AddResult(reply)))
        val rootRequest = request.copy(root = 0, parent = 0)
        assertEquals(0L, parser.parse(response, rootRequest, 1000)!!.root)
        assertEquals(0L, parser.parse(response, rootRequest, 1000)!!.parent)
        assertNull(parser.parse(response, request.copy(oid = 0), 1000))
        assertNull(parser.parse(response, request.copy(type = 0), 1000))
    }

    @Test fun asyncCompletionBeforePublisherReturnsIsStillTrackedExactlyOnce() {
        val tracker = CommentCallTracker()
        val call = Any()
        val executor = Executors.newSingleThreadExecutor()
        try {
            tracker.within(request) {
                tracker.onEnqueue(call)
                // Models Ai.a.o: execute runs on a worker before o returns its Call.
                assertEquals(request, executor.submit(Callable { tracker.take(call) }).get(5, TimeUnit.SECONDS))
            }
            assertNull(tracker.take(call))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun returnedCallsAreIsolatedEvenWhenExecutedInReverseOrder() {
        val tracker = CommentCallTracker()
        val first = Any()
        val second = Any()
        val other = request.copy(oid = 200, message = "另一条", source = "Ai.a.p")
        tracker.track(first, request)
        tracker.track(second, other)
        assertEquals(other, tracker.take(second))
        assertEquals(request, tracker.take(first))
        assertNull(tracker.take(Any()))
        assertNull(tracker.take(first))
    }

    @Test fun nestedScopesRestoreAfterHostExceptionsAndDoNotTagUnrelatedRequests() {
        val tracker = CommentCallTracker()
        val inner = request.copy(oid = 200)
        val expected = IllegalStateException("host failure")
        tracker.within(request) {
            val thrown = assertThrows(IllegalStateException::class.java) {
                tracker.within(inner) {
                    val call = Any()
                    tracker.onEnqueue(call)
                    assertEquals(inner, tracker.take(call))
                    throw expected
                }
            }
            assertSame(expected, thrown)
            val call = Any()
            tracker.onEnqueue(call)
            assertEquals(request, tracker.take(call))
        }
        val unrelated = Any()
        tracker.onEnqueue(unrelated)
        assertNull(tracker.take(unrelated))
    }

    @Test fun requestScopeDoesNotLeakToOtherThreads() {
        val tracker = CommentCallTracker()
        val call = Any()
        val executor = Executors.newSingleThreadExecutor()
        try {
            tracker.within(request) {
                executor.submit { tracker.onEnqueue(call) }.get(5, TimeUnit.SECONDS)
                assertNull(tracker.take(call))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun concurrentDuplicatesLaunchOnlyOnceAndMemoryIsBounded() {
        val ids = RecentCommentIds(2)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..32).map { Callable { ids.claim(101) } })
            assertEquals(1, results.count { it.get() })
            assertFalse(ids.claim(0))
            assertFalse(ids.claim(-1))
            assertTrue(ids.claim(102))
            assertFalse(ids.claim(101))
            assertTrue(ids.claim(103))
            assertTrue(ids.claim(101)) // oldest ID is evicted at capacity
        } finally {
            executor.shutdownNow()
        }
    }
}
