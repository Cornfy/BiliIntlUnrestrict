package me.bili.unrestrict.hook

import io.github.libxposed.api.XposedModule
import me.bili.unrestrict.util.XLog

/** Verified against international 6.4.0: requestAddReply returns an unboxed Result. */
internal class KntrCommentCaptureHook(
    private val module: XposedModule,
    private val readCookie: () -> String,
    private val onPublished: (PublishedComment, String, String) -> Unit
) {
    private val installed = HashSet<java.lang.reflect.Method>()

    @Synchronized
    fun install(loader: ClassLoader) {
        try {
            val parser = KntrCommentResponseParser(
                loader.loadClass("kntr.common.comment.publish.api.a"))
            val method = loader.loadClass("kntr.common.comment.publish.api.b")
                .getDeclaredMethod("b",
                    loader.loadClass("com.bapis.bilibili.main.community.reply.v1.KAddReplyReq"),
                    loader.loadClass("kotlin.coroutines.jvm.internal.ContinuationImpl"))
            if (method in installed) return
            module.hook(method).intercept { chain ->
                // Preserve host exceptions. A resumed invocation has a null request;
                // all record metadata comes from the completed reply instead.
                val result = chain.proceed()
                try {
                    parser.parse(result, System.currentTimeMillis())?.let {
                        onPublished(it, readCookie(), "kntr.requestAddReply")
                    }
                } catch (t: Throwable) {
                    XLog.w("[CommentCapture] Kntr 返回解析失败: ${t.javaClass.simpleName}")
                }
                result
            }
            installed.add(method)
            XLog.i("[CommentCapture] Kntr requestAddReply 挂载成功")
        } catch (t: Throwable) {
            XLog.w("[CommentCapture] Kntr 挂载失败: ${t.message}")
        }
    }
}
