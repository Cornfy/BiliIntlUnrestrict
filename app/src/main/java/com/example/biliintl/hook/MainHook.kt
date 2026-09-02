package com.example.biliintl.hook

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.URL

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TARGET_PACKAGE = "com.bilibili.app.in"
        private const val TAG = "BiliTracer"

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        log("🚀 注入目标进程: ${lpparam.processName}")

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val classLoader = (param.thisObject as Application).classLoader
                    
                    // 1. 挂载全局 OkHttp 抓包探针
                    installOkHttpTracer(classLoader)

                    // 2. 挂载 gRPC 响应实体探针
                    installGrpcTracer(classLoader)

                    // 3. 挂载青少年模式关键状态变更探针
                    installStateTracer(classLoader)
                }
            }
        )
    }

    /**
     * 1. 抓取所有 HTTP/HTTPS 网络请求（URL、参数、响应码）
     */
    private fun installOkHttpTracer(classLoader: ClassLoader) {
        try {
            val realCallClass = XposedHelpers.findClassIfExists("okhttp3.RealCall", classLoader)
            if (realCallClass != null) {
                // 拦截异步请求 enqueue
                XposedHelpers.findAndHookMethod(
                    realCallClass,
                    "enqueue",
                    "okhttp3.Callback",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val request = XposedHelpers.callMethod(param.thisObject, "request")
                            val url = XposedHelpers.callMethod(request, "url").toString()

                            // 过滤出与青少年模式、模式控制、用户状态相关的请求
                            if (url.contains("teenager", ignoreCase = true) ||
                                url.contains("mode", ignoreCase = true) ||
                                url.contains("age", ignoreCase = true) ||
                                url.contains("user/status", ignoreCase = true)
                            ) {
                                val method = XposedHelpers.callMethod(request, "method")
                                log("📡 [OkHttp 发包] $method -> $url")
                            }
                        }
                    }
                )
                log("✅ OkHttp 抓包探针挂载成功")
            }
        } catch (t: Throwable) {
            log("⚠️ OkHttp 探针挂载失败: ${t.message}")
        }
    }

    /**
     * 2. 抓取 gRPC 服务端下发的真实数据
     */
    private fun installGrpcTracer(classLoader: ClassLoader) {
        try {
            val userModelClass = XposedHelpers.findClassIfExists(
                "com.bapis.bilibili.app.interfaces.v1.UserModel",
                classLoader
            )
            if (userModelClass != null) {
                // 观察服务端原始返回的每个字段
                XposedHelpers.findAndHookMethod(
                    userModelClass,
                    "getMustTeen",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            log("📥 [gRPC 下发] UserModel.getMustTeen() 原始值 = ${param.result}")
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    userModelClass,
                    "getAge",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            log("📥 [gRPC 下发] UserModel.getAge() 原始值 = ${param.result}")
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            log("⚠️ gRPC 探针挂载失败: ${t.message}")
        }
    }

    /**
     * 3. 抓取本地状态与弹窗触发逻辑
     */
    private fun installStateTracer(classLoader: ClassLoader) {
        try {
            val statusClass = XposedHelpers.findClassIfExists(
                "com.bilibili.teenagersmode.model.TeenagersModeStatus",
                classLoader
            )
            if (statusClass != null) {
                XposedHelpers.findAndHookMethod(
                    statusClass,
                    "isValid",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val thisObj = param.thisObject
                            val status = XposedHelpers.getIntField(thisObj, "status")
                            val mustTeen = XposedHelpers.getBooleanField(thisObj, "mustTeen")
                            val isOverseas = XposedHelpers.getBooleanField(thisObj, "isOverseas")
                            log("📊 [本地状态机] TeenagersModeStatus 快照 -> status=$status, mustTeen=$mustTeen, isOverseas=$isOverseas")
                        }
                    }
                )
            }
        } catch (t: Throwable) {}
    }
}
