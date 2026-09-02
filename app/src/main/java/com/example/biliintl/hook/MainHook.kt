package com.example.biliintl.hook

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TARGET_PACKAGE = "com.bilibili.app.in"
        private const val TAG = "BiliIntlHook"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedBridge.log("[$TAG] 🚀 成功拦截到 B站国际版主进程: ${lpparam.processName}")

        // 核心：等待 Application 启动并加载完所有 Split Dex 后再执行 Hook
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val classLoader = (param.thisObject as Application).classLoader
                    XposedBridge.log("[$TAG] 📦 Application attach 成功，开始注入业务 Hook...")
                    
                    hookAllTeenagerRestrictions(classLoader)
                }
            }
        )
    }

    private fun hookAllTeenagerRestrictions(classLoader: ClassLoader) {
        // 1. 拦截服务端下发的 UserModel (gRPC 核心)
        try {
            val userModelClass = XposedHelpers.findClassIfExists(
                "com.bapis.bilibili.app.interfaces.v1.UserModel",
                classLoader
            )
            if (userModelClass != null) {
                XposedHelpers.findAndHookMethod(userModelClass, "getMustTeen", XC_MethodReplacement.returnConstant(false))
                XposedHelpers.findAndHookMethod(userModelClass, "getIsForced", XC_MethodReplacement.returnConstant(false))
                XposedHelpers.findAndHookMethod(userModelClass, "getAge", XC_MethodReplacement.returnConstant(22))
                XposedHelpers.findAndHookMethod(userModelClass, "getIsOverseas", XC_MethodReplacement.returnConstant(false))
                XposedBridge.log("[$TAG] ✅ UserModel gRPC 拦截成功")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] ❌ UserModel Hook 失败: ${t.message}")
        }

        // 2. 拦截本地状态枚举判定 (AgeCheck)
        try {
            XposedHelpers.findAndHookMethod(
                "com.bilibili.teenagersmode.model.TeenagersModeAgeCheck",
                classLoader,
                "toIntEnum",
                XC_MethodReplacement.returnConstant(4) // 4 = 成年人
            )
            XposedBridge.log("[$TAG] ✅ TeenagersModeAgeCheck 拦截成功")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] ❌ AgeCheck Hook 失败: ${t.message}")
        }

        // 3. 拦截全局拦截 Activity 的启动
        try {
            val forceActivityClass = XposedHelpers.findClassIfExists(
                "com.bilibili.teenagersmode.ui.TeenagersForceModeGuardianBindActivity",
                classLoader
            )
            if (forceActivityClass != null) {
                XposedHelpers.findAndHookMethod(
                    forceActivityClass,
                    "onCreate",
                    android.os.Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            XposedBridge.log("[$TAG] 🛑 阻断强制监护人弹窗 Activity 启动！")
                            val activity = param.thisObject as android.app.Activity
                            activity.finish()
                            param.result = null
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] ❌ ForceActivity Hook 失败: ${t.message}")
        }
    }
}
