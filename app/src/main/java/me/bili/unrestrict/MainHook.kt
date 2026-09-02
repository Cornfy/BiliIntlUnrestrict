package me.bili.unrestrict

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlinx.coroutines.*
import me.bili.unrestrict.data.db.CommentFraudRecord
import me.bili.unrestrict.data.repository.CommentFraudRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainHook : XposedModule() {

    companion object {
        private const val TARGET_PACKAGE = "com.bilibili.app.in"
        private const val TAG = "BiliHook"

        // 专用路人 rawCurl 发包客户端 (对齐 biliSendCheck Web 发包规范)
        private val rawCurlClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        private val hookScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())

        fun log(msg: String) = Log.i(TAG, msg)

        fun showToast(context: Context?, text: String) {
            if (context == null) return
            mainHandler.post {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName != TARGET_PACKAGE) return
        log("🚀 [Phase 1] 模块已载入 B站包")

        param.defaultClassLoader?.let { classLoader ->
            applyAdultOverrides(classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return
        log("🚀 [Phase 2] 应用 Dex 全部就绪，开始挂载核心发评 Hook")

        param.classLoader?.let { classLoader ->
            applyAdultOverrides(classLoader)
            applyCommentPublisherHook(classLoader)
        }
    }

    // ==========================================
    // 1. 青少年模式解除
    // ==========================================
    private fun applyAdultOverrides(classLoader: ClassLoader) {
        try {
            val modelStatusClass = classLoader.loadClass("com.bapis.bilibili.app.interfaces.v1.ModelStatus")
            modelStatusClass.getDeclaredMethod("getNumber").let { m -> hook(m).intercept { _ -> 0 } }
        } catch (_: Throwable) {}

        try {
            val userModelClass = classLoader.loadClass("com.bapis.bilibili.app.interfaces.v1.UserModel")
            userModelClass.getDeclaredMethod("getAge").let { m -> hook(m).intercept { _ -> 22 } }
            userModelClass.getDeclaredMethod("getMustTeen").let { m -> hook(m).intercept { _ -> false } }
            userModelClass.getDeclaredMethod("getIsForced").let { m -> hook(m).intercept { _ -> false } }
            userModelClass.getDeclaredMethod("getIsOverseas").let { m -> hook(m).intercept { _ -> false } }
            userModelClass.getDeclaredMethod("getIsParentControl").let { m -> hook(m).intercept { _ -> false } }
        } catch (_: Throwable) {}

        try {
            val ageCheckClass = classLoader.loadClass("com.bilibili.teenagersmode.model.TeenagersModeAgeCheck")
            ageCheckClass.getDeclaredMethod("toIntEnum").let { m -> hook(m).intercept { _ -> 4 } }
        } catch (_: Throwable) {}

        try {
            val teenagersKtClass = classLoader.loadClass("com.bilibili.app.comm.restrict.utils.TeenagersModeKt")
            for (method in teenagersKtClass.declaredMethods) {
                if (method.returnType == Boolean::class.javaPrimitiveType) {
                    hook(method).intercept { _ -> false }
                } else if (method.returnType == Int::class.javaPrimitiveType) {
                    hook(method).intercept { _ -> 22 }
                }
            }
        } catch (_: Throwable) {}
    }

    // ==========================================
    // 2. 发评全自动拦截：直接物理直写 SQLite 共享数据库
    // ==========================================
    private fun applyCommentPublisherHook(classLoader: ClassLoader) {
        val targets = listOf(
            "com.bilibili.app.comment3.data.source.v1.PublisherDataSourceV1\$post\$2",
            "com.bilibili.app.comment3.data.source.v1.PublisherDataSourceV1\$lightPost\$2"
        )

        for (targetName in targets) {
            try {
                val clazz = classLoader.loadClass(targetName)
                clazz.getDeclaredMethod("invokeSuspend", Any::class.java).let { method ->
                    hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (result != null) {
                            handlePublisherResult(result)
                        }
                        result
                    }
                }
                log("✅ [Hook] $targetName 挂载成功")
            } catch (t: Throwable) {
                log("⚠️ 协程 Hook 失败 $targetName: ${t.message}")
            }
        }
    }

    /**
     * 处理协程返回的 CommentAddReply 对象
     */
    private fun handlePublisherResult(replyObj: Any) {
        try {
            val dField = replyObj.javaClass.getField("d")
            val commentItem = dField.get(replyObj) ?: return

            val itemClass = commentItem.javaClass
            val rpid = itemClass.getField("a").getLong(commentItem)
            val oid = itemClass.getField("b").getLong(commentItem)
            val type = itemClass.getField("c").getLong(commentItem).toInt()
            val uid = itemClass.getField("d").getLong(commentItem)
            val root = itemClass.getField("e").getLong(commentItem)
            val parent = itemClass.getField("f").getLong(commentItem)
            val ctime = itemClass.getField("q").getLong(commentItem) * 1000L

            // 🎯 精确匹配 RichText(raw=xxx) 彻底杜绝 overDraw=false 干扰！
            val itemStr = commentItem.toString()
            val rawMatch = Regex("""RichText\(raw=([^,\)]+)""").find(itemStr)
            val message = rawMatch?.groupValues?.get(1) ?: "已发表评论"

            log("🎯 [Publisher] 成功捕获新发评: rpid=$rpid, oid=$oid, msg=$message")
            processCommentLifecycle(rpid, oid, type, root, parent, uid, message, ctime)
        } catch (e: Exception) {
            log("❌ handlePublisherResult 失败: ${e.message}")
        }
    }

    /**
     * 发评全生命周期检测流程
     */
    private fun processCommentLifecycle(
        rpid: Long,
        oid: Long,
        type: Int,
        root: Long,
        parent: Long,
        uid: Long,
        message: String,
        ctime: Long
    ) {
        // 1. 发评瞬间：初态 UNKNOWN 提交
        sendCommentRecordToModule(rpid, oid, type, root, parent, uid, message, "UNKNOWN", ctime)

        // 2. 协程缓冲 4 秒等待服务器主从同步
        hookScope.launch {
            delay(4000L)
            val status = probeWithRawCurl(oid, type, rpid, root)
            log("🔍 [RawCurl Probe] 真实存活定性: rpid=$rpid -> $status")

            // 更新真实状态
            sendCommentRecordToModule(rpid, oid, type, root, parent, uid, message, status, ctime)

            // 3. 前台友好 Toast 提示
            val context = getApplicationContext()
            when (status) {
                "NORMAL" -> showToast(context, "🟢 发评反诈: 评论正常 (路人可见)")
                "SHADOW_BANNED" -> showToast(context, "🔴 发评反诈: 评论被仅自己可见 (ShadowBan)")
                "DELETED" -> showToast(context, "⚫ 发评反诈: 评论已被系统秒删")
            }
        }
    }

    /**
     * 显式指定组件精准投递 (豁免任何系统包可见性隔离)
     */
    private fun sendCommentRecordToModule(
        rpid: Long,
        oid: Long,
        type: Int,
        root: Long,
        parent: Long,
        uid: Long,
        message: String,
        status: String,
        postTime: Long
    ) {
        try {
            val context = getApplicationContext() ?: return
            val intent = android.content.Intent("me.bili.unrestrict.ACTION_INSERT_COMMENT").apply {
                component = android.content.ComponentName("me.bili.unrestrict", "me.bili.unrestrict.ipc.CommentReceiver")
                putExtra("rpid", rpid)
                putExtra("oid", oid)
                putExtra("type", type)
                putExtra("root", root)
                putExtra("parent", parent)
                putExtra("uid", uid)
                putExtra("message", message)
                putExtra("status", status)
                putExtra("post_time", postTime)
                addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
            log("📡 显式广播已定向投递: rpid=$rpid, status=$status")
        } catch (e: Exception) {
            log("❌ 发送广播异常: ${e.message}")
        }
    }

    /**
     * 【专用路人 rawCurl 发包器】1:1 对齐 biliSendCheck 浏览器请求规范
     */
    private fun probeWithRawCurl(oid: Long, type: Int, rpid: Long, root: Long): String {
        return try {
            val url = if (root > 0L) {
                // 楼中楼请求
                "https://api.bilibili.com/x/v2/reply/reply?oid=$oid&type=$type&root=$root&ps=20&pn=1"
            } else {
                // 根评论请求 (mode=2 时间倒序)
                "https://api.bilibili.com/x/v2/reply/main?oid=$oid&type=$type&mode=2&next=0&seek_rpid=$rpid&ps=20"
            }

            // 构造 100% 对齐 Web 浏览器端标准的抓包报文
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Origin", "https://www.bilibili.com")
                .header("Referer", "https://www.bilibili.com")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            val response = rawCurlClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            log("📡 [RawCurl Body] 返回片段: ${body.take(200)}")

            val rpidPattern = """"rpid":\s*$rpid""".toRegex()

            when {
                // 1. 成功在路人回包中找到 RPID -> 正常
                rpidPattern.containsMatchIn(body) -> "NORMAL"
                // 2. 接口明确提示 12022 (已被删除) -> 秒删
                body.contains("\"code\":12022") || body.contains("\"code\": 12022") -> "DELETED"
                // 3. 只有当 code=0 且正文里找不着，才是真实确凿的 ShadowBan
                body.contains("\"code\":0") || body.contains("\"code\": 0") -> "SHADOW_BANNED"
                // 4. 其余情况 (如 WAF 触发、-352、-400 等)，归为 UNKNOWN 绝不武断误判
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            log("❌ RawCurl 异常: ${e.message}")
            "UNKNOWN"
        }
    }

    private fun getApplicationContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            currentApplicationMethod.invoke(null) as? Context
        } catch (_: Exception) {
            null
        }
    }
}
