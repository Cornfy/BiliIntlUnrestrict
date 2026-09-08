package me.bili.unrestrict.hook

import java.util.WeakHashMap

internal data class PublishedComment(
    val rpid: Long,
    val oid: Long,
    val type: Int,
    val root: Long,
    val parent: Long,
    val uid: Long,
    val message: String,
    val postTime: Long
)

internal data class CommentPostRequest(
    val oid: Long,
    val type: Int,
    val root: Long,
    val parent: Long,
    val message: String,
    val cookie: String,
    val source: String
)

/** Only the Calls created by the two verified legacy publishers are tracked. */
internal class CommentCallTracker {
    private val current = ThreadLocal<CommentPostRequest?>()
    // CB0.a does not override equals/hashCode in the verified 6.4.0 APK.
    // Weak keys also release abandoned Calls that never execute.
    private val pending = WeakHashMap<Any, CommentPostRequest>()

    fun <T> within(request: CommentPostRequest?, block: () -> T): T {
        val previous = current.get()
        current.set(request)
        return try {
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun onEnqueue(call: Any) {
        current.get()?.let { track(call, it) }
    }

    @Synchronized
    fun track(call: Any, request: CommentPostRequest) {
        pending[call] = request
    }

    @Synchronized
    fun take(call: Any): CommentPostRequest? = pending.remove(call)
}

/** Atomically deduplicate before launching probes; Room REPLACE alone cannot do this. */
internal class RecentCommentIds(private val capacity: Int = 2048) {
    init {
        require(capacity > 0)
    }

    private val ids = LinkedHashSet<Long>()

    @Synchronized
    fun claim(rpid: Long): Boolean {
        if (rpid <= 0L || !ids.add(rpid)) return false
        if (ids.size > capacity) {
            val oldest = ids.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }
}

/** Reflection stays in the host ClassLoader; no casts to the module's Retrofit/OkHttp. */
internal class LegacyCommentResponseParser(private val addResultClass: Class<*>) {
    fun parse(response: Any?, request: CommentPostRequest, now: Long): PublishedComment? {
        if (response == null) return null
        val raw = response.field("a") ?: return null
        if (raw.javaClass.getMethod("p").invoke(raw) != true) return null
        val body = response.field("b") ?: return null
        if (body.number("code") != 0L) return null
        val data = body.field("data") ?: return null
        if (!addResultClass.isInstance(data)) return null
        val reply = data.field("reply") ?: return null
        val rpid = reply.number("mRpId")
        if (rpid <= 0L) return null

        val oid = reply.number("mOid").takeIf { it > 0L } ?: request.oid
        val type = reply.number("mType").takeIf { it > 0L }?.toInt() ?: request.type
        if (oid <= 0L || type <= 0) return null
        val seconds = reply.number("mCtime")
        val content = reply.field("mContent")
        val message = (content?.field("mMsg") as? String).orEmpty().ifBlank { request.message }
        return PublishedComment(
            rpid, oid, type,
            reply.number("mRootId").takeIf { it > 0L } ?: request.root,
            reply.number("mParentId").takeIf { it > 0L } ?: request.parent,
            reply.number("mMid"), message,
            if (seconds in 1..(Long.MAX_VALUE / 1000)) seconds * 1000 else now
        )
    }

    private fun Any.field(name: String): Any? = javaClass.getField(name).get(this)
    private fun Any.number(name: String): Long = (field(name) as Number).toLong()
}
