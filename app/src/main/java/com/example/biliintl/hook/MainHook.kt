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
        
        // 🚀 日常使用设为 false（完全静默，零性能开销）；排查问题时改成 true
        private const val DEBUG = true

        inline fun log(msg: () -> String) {
            if (DEBUG) {
                XposedBridge.log("[$TAG] ${msg()}")
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val classLoader = (param.thisObject as Application).classLoader
                    log { "📦 注入核心成年人状态修正..." }
                    applyAdultOverrides(classLoader)
                }
            }
        )
    }

    private fun applyAdultOverrides(classLoader: ClassLoader) {
        // 1. 修复 gRPC 下发的 age=0 问题，直接声明为 22 岁成年人
        try {
            val userModelClass = XposedHelpers.findClassIfExists(
                "com.bapis.bilibili.app.interfaces.v1.UserModel",
                classLoader
            )
            if (userModelClass != null) {
                XposedHelpers.findAndHookMethod(userModelClass, "getAge", XC_MethodReplacement.returnConstant(22))
                XposedHelpers.findAndHookMethod(userModelClass, "getMustTeen", XC_MethodReplacement.returnConstant(false))
                XposedHelpers.findAndHookMethod(userModelClass, "getIsForced", XC_MethodReplacement.returnConstant(false))
                XposedHelpers.findAndHookMethod(userModelClass, "getIsOverseas", XC_MethodReplacement.returnConstant(false))
                log { "✅ UserModel 年龄与地区覆写成功 (age=22, isOverseas=false)" }
            }
        } catch (t: Throwable) {
            log { "❌ UserModel Hook 异常: ${t.message}" }
        }

        // 2. 锁定年龄枚举档位为 4 (>=18岁)
        try {
            XposedHelpers.findAndHookMethod(
                "com.bilibili.teenagersmode.model.TeenagersModeAgeCheck",
                classLoader,
                "toIntEnum",
                XC_MethodReplacement.returnConstant(4)
            )
            log { "✅ TeenagersModeAgeCheck 枚举锁定为 4 (成年人)" }
        } catch (t: Throwable) {
            log { "❌ AgeCheck Hook 异常: ${t.message}" }
        }
    }
}
