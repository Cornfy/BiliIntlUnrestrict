package me.bili.unrestrict.hook

import io.github.libxposed.api.XposedModule
import me.bili.unrestrict.util.XLog
import java.lang.reflect.Method

/** Version-specific supplement for com.bilibili.app.in 6.4.0 (9100300). */
internal class LegacyCommentCaptureHook(
    private val module: XposedModule,
    private val readCookie: () -> String,
    private val onPublished: (PublishedComment, String, String) -> Unit
) {
    private val tracker = CommentCallTracker()
    private val installed = HashSet<Method>()

    @Synchronized
    fun install(loader: ClassLoader) {
        try {
            val publisher = loader.loadClass("Ai.a")
            val callClass = loader.loadClass("CB0.a")
            val execute = callClass.getDeclaredMethod("execute")
            check(execute.returnType.name == "retrofit2.z")
            val enqueue = callClass.getDeclaredMethod("x", loader.loadClass("retrofit2.d"))
            val parser = LegacyCommentResponseParser(
                loader.loadClass("com.bilibili.app.comm.comment2.model.BiliCommentAddResult")
            )

            if (execute !in installed) {
                module.hook(execute).intercept { chain ->
                    val request = chain.thisObject?.let { tracker.take(it) }
                    // Preserve the original result and exception, including failed requests.
                    val response = chain.proceed()
                    if (request != null) observe("${request.source} 响应解析") {
                        val comment = parser.parse(response, request, System.currentTimeMillis())
                        if (comment != null) onPublished(comment, request.cookie, request.source)
                        else XLog.d("[CommentCapture] ${request.source} 无有效成功发评响应，跳过")
                    }
                    response
                }
                installed.add(execute)
            }
            if (enqueue !in installed) {
                module.hook(enqueue).intercept { chain ->
                    // Ai.a.o enqueues BEFORE returning. Tag before the worker can execute.
                    val call = chain.thisObject
                    if (call != null) observe("旧发评入队标记") { tracker.onEnqueue(call) }
                    try {
                        chain.proceed()
                    } catch (t: Throwable) {
                        if (call != null) tracker.take(call)
                        throw t
                    }
                }
                installed.add(enqueue)
            }

            // Exact signatures, not names/argument counts alone: fail visibly on APK changes.
            installPublisher(publisher, "o", listOf(
                "android.content.Context", "long", "int", "long", "long", "int", "int",
                "java.lang.String", "java.lang.String", "java.lang.String", "java.util.ArrayList",
                "long", "int", "java.lang.String", "java.lang.String", "java.lang.String",
                "java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String",
                "java.util.HashMap", "BB0.a"
            ))
            installPublisher(publisher, "p", listOf(
                "com.bilibili.app.comm.comment2.CommentContext", "java.lang.String",
                "long", "long", "long", "java.lang.String", "java.lang.String",
                "java.lang.String", "java.lang.String", "boolean", "boolean", "boolean",
                "int", "kotlin.Pair"
            ))
        } catch (t: Throwable) {
            XLog.w("[CommentCapture] 旧发评捕获挂载失败（已验证版本 6.4.0）: ${t.message}")
        }
    }

    private fun installPublisher(owner: Class<*>, name: String, signature: List<String>) {
        try {
            val method = owner.declaredMethods.single {
                it.name == name && it.returnType.name == "CB0.a" &&
                    it.parameterTypes.map { type -> type.name } == signature
            }
            if (method in installed) return
            module.hook(method).intercept { chain ->
                var request: CommentPostRequest? = null
                observe("Ai.a.$name 请求参数") {
                    request = snapshot(name, chain.args)
                }
                if (name == "o") {
                    // Do not re-tag the returned Call: it may already have completed.
                    tracker.within(request) { chain.proceed() }
                } else {
                    val call = chain.proceed()
                    val captured = request
                    if (call != null && captured != null) observe("Ai.a.p 请求标记") {
                        tracker.track(call, captured)
                    }
                    call
                }
            }
            installed.add(method)
            XLog.i("[CommentCapture] Ai.a.$name 旧发评入口挂载成功")
        } catch (t: Throwable) {
            XLog.w("[CommentCapture] Ai.a.$name 挂载失败: ${t.message}")
        }
    }

    private fun snapshot(name: String, args: List<Any?>): CommentPostRequest {
        fun number(index: Int) = (args[index] as Number).toLong()
        return if (name == "o") {
            CommentPostRequest(number(1), number(2).toInt(), number(3), number(4),
                (args[7] as? String).orEmpty(), readCookie(), "Ai.a.o")
        } else {
            val context = requireNotNull(args[0])
            val oid = (context.javaClass.getField("b").get(context) as Number).toLong()
            val type = (context.javaClass.getField("c").get(context) as Number).toInt()
            CommentPostRequest(oid, type, number(2), number(3),
                (args[5] as? String).orEmpty(), readCookie(), "Ai.a.p")
        }
    }

    private inline fun observe(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // An observer failure must not change the host's publishing/callback behavior.
            XLog.w("[CommentCapture] $operation 失败: ${t.message}")
        }
    }
}
