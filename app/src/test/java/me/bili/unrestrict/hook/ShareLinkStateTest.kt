package me.bili.unrestrict.hook

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShareLinkStateTest {
    private val first = ShareLinkFormatter.Content("【A】", "https://www.bilibili.com/video/BV1xx411c7mD?p=2")
    private val second = ShareLinkFormatter.Content("【B】", "https://www.bilibili.com/video/BV1xx411c7mD?p=3")

    @Test fun outOfOrderResponsesUseTheirOwnBundleAndChannel() {
        val state = ShareLinkState<Any>()
        val a = Any()
        val b = Any()
        state.capture(a, "COPY", first)
        state.capture(b, "GENERIC", second)
        state.within(b, "GENERIC", true) { assertEquals(second, state.current) }
        state.within(a, "COPY", true) { assertEquals(first, state.current) }
        state.within(a, "GENERIC", true) { assertNull(state.current) }
        state.within(a, "WEIXIN", true) { assertNull(state.current) }
        state.within(Any(), "COPY", true) { assertNull(state.current) }
        state.within(a, "COPY", false) { assertNull(state.current) }
        assertNull(state.current)
    }

    @Test fun unrelatedNestedShareClearsContextAndExceptionsRestoreIt() {
        val state = ShareLinkState<Any>()
        val key = Any()
        state.capture(key, "COPY", first)
        state.within(key, "COPY", true) {
            try {
                state.within(key, "biliDynamic", true) {
                    assertNull(state.current)
                    throw IllegalStateException("host share failed")
                }
                fail("Expected exception")
            } catch (_: IllegalStateException) {
                assertEquals(first, state.current)
            }
        }
        assertNull(state.current)
    }

    @Test fun reusedBundleCannotKeepAnEarlierVideoOnFailedCapture() {
        val state = ShareLinkState<Any>()
        val key = Any()
        state.capture(key, "COPY", first)
        state.capture(key, "COPY", null)
        state.within(key, "COPY", true) { assertNull(state.current) }
        state.capture(key, "biliIm", second)
        state.within(key, "biliIm", true) { assertNull(state.current) }
    }

    @Test fun concurrentDispatchesDoNotMixVideos() {
        val state = ShareLinkState<Any>()
        val keys = listOf(Any(), Any())
        val contents = listOf(first, second)
        keys.forEachIndexed { index, key -> state.capture(key, "COPY", contents[index]) }
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = keys.mapIndexed { index, key ->
                executor.submit {
                    state.within(key, "COPY", true) {
                        barrier.await(5, TimeUnit.SECONDS)
                        assertEquals(contents[index], state.current)
                    }
                    assertNull(state.current)
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
