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
        private const val TAG = "BiliTracer"

        private fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
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
                    applyBulletproofOverrides(classLoader)
                }
            }
        )
    }

    private fun applyBulletproofOverrides(classLoader: ClassLoader) {
        // 1. 拦截 gRPC ModelStatus 枚举（强制返回 0 = NORMAL 正常模式）
        try {
            val modelStatusClass = XposedHelpers.findClassIfExists(
                "com.bapis.bilibili.app.interfaces.v1.ModelStatus",
                classLoader
            )
            if (modelStatusClass != null) {
                XposedHelpers.findAndHookMethod(modelStatusClass, "getNumber", XC_MethodReplacement.returnConstant(0))
                log("✅ [1] ModelStatus.getNumber 强制锁定为 0 (NORMAL)")
            }
        } catch (t: Throwable) {
            log("❌ [1] ModelStatus Hook 失败: ${t.message}")
        }

        // 2. 拦截 gRPC UserModel
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
                XposedHelpers.findAndHookMethod(userModelClass, "getIsParentControl", XC_MethodReplacement.returnConstant(false))
                log("✅ [2] UserModel 全字段成年人覆写成功")
            }
        } catch (t: Throwable) {
            log("❌ [2] UserModel Hook 失败: ${t.message}")
        }

        // 3. 拦截本地顶级判断工具类 TeenagersModeKt
        try {
            val teenagersKtClass = XposedHelpers.findClassIfExists(
                "com.bilibili.app.comm.restrict.utils.TeenagersModeKt",
                classLoader
            )
            if (teenagersKtClass != null) {
                for (method in teenagersKtClass.declaredMethods) {
                    // 所有返回 boolean 的限制判断方法一律返回 false
                    if (method.returnType == Boolean::class.javaPrimitiveType) {
                        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false))
                    }
                    // 所有返回年龄/状态的 int 方法返回 22 或 0
                    if (method.returnType == Int::class.javaPrimitiveType) {
                        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(22))
                    }
                }
                log("✅ [3] TeenagersModeKt 全局限制工具类彻底中和")
            }
        } catch (t: Throwable) {
            log("❌ [3] TeenagersModeKt Hook 失败: ${t.message}")
        }

        // 4. 拦截本地状态模型 TeenagersModeStatus
        try {
            val statusClass = XposedHelpers.findClassIfExists(
                "com.bilibili.teenagersmode.model.TeenagersModeStatus",
                classLoader
            )
            if (statusClass != null) {
                XposedHelpers.findAndHookMethod(statusClass, "isValid", XC_MethodReplacement.returnConstant(true))
                // 拦截构造函数，强制初始化为正常模式
                XposedBridge.hookAllConstructors(statusClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject
                        XposedHelpers.setIntField(obj, "status", 0)
                        XposedHelpers.setBooleanField(obj, "mustTeen", false)
                        XposedHelpers.setBooleanField(obj, "isForce", false)
                        XposedHelpers.setBooleanField(obj, "isOverseas", false)
                        XposedHelpers.setBooleanField(obj, "isParentControl", false)
                    }
                })
                log("✅ [4] TeenagersModeStatus 状态模型彻底净化")
            }
        } catch (t: Throwable) {
            log("❌ [4] StatusModel Hook 失败: ${t.message}")
        }
    }
}
