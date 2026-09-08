package me.bili.unrestrict.hook

/** Host-side getters are resolved once; no dependency on the host protobuf runtime. */
internal class KntrCommentResponseParser(private val successClass: Class<*>) {
    private val replyField = successClass.getField("a")
    private val replyClass = replyField.type
    private val id = replyClass.getMethod("getId")
    private val oid = replyClass.getMethod("getOid")
    private val type = replyClass.getMethod("getType")
    private val root = replyClass.getMethod("getRoot")
    private val parent = replyClass.getMethod("getParent")
    private val mid = replyClass.getMethod("getMid")
    private val ctime = replyClass.getMethod("getCtime")
    private val content = replyClass.getMethod("getContent")
    private val message = content.returnType.getMethod("getMessage")

    fun parse(result: Any?, now: Long): PublishedComment? {
        // Ignores COROUTINE_SUSPENDED and Result.Failure, accepting both immediate
        // completion and the re-entry from requestAddReply's continuation.
        if (!successClass.isInstance(result)) return null
        val reply = replyField.get(result) ?: return null
        val rpid = (id.invoke(reply) as Number).toLong()
        val objectId = (oid.invoke(reply) as Number).toLong()
        val subjectType = (type.invoke(reply) as Number).toLong()
        val rootId = (root.invoke(reply) as Number).toLong()
        val parentId = (parent.invoke(reply) as Number).toLong()
        if (rpid <= 0 || objectId <= 0 || subjectType !in 1..Int.MAX_VALUE.toLong() ||
            rootId < 0 || parentId < 0) return null
        val seconds = (ctime.invoke(reply) as Number).toLong()
        val text = content.invoke(reply)?.let { message.invoke(it) as? String }.orEmpty()
        return PublishedComment(rpid, objectId, subjectType.toInt(), rootId, parentId,
            (mid.invoke(reply) as Number).toLong(), text,
            if (seconds in 1..Long.MAX_VALUE / 1000) seconds * 1000 else now)
    }
}
