package com.example.bilipai.hook

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

        XposedBridge.log("[$TAG] 成功注入 B站国际版: ${lpparam.packageName}")

        hookServerModel(lpparam.classLoader)
        hookLocalAgeCheck(lpparam.classLoader)
        hookStatusModel(lpparam.classLoader)
    }

    /**
     * 1. 核心源头：拦截 gRPC 服务端下发的 UserModel 实体
     */
    private fun hookServerModel(classLoader: ClassLoader) {
        try {
            val userModelClass = XposedHelpers.findClassIfExists(
                "com.bapis.bilibili.app.interfaces.v1.UserModel",
                classLoader
            ) ?: return

            // 强制禁止锁定青少年模式
            XposedHelpers.findAndHookMethod(
                userModelClass,
                "getMustTeen",
                XC_MethodReplacement.returnConstant(false)
            )

            // 强制关闭强制生效标志
            XposedHelpers.findAndHookMethod(
                userModelClass,
                "getIsForced",
                XC_MethodReplacement.returnConstant(false)
            )

            // 强制返回成年人年龄 (如 22 岁)
            XposedHelpers.findAndHookMethod(
                userModelClass,
                "getAge",
                XC_MethodReplacement.returnConstant(22)
            )

            XposedBridge.log("[$TAG] ✅ UserModel gRPC 下发拦截已就绪")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] ❌ UserModel Hook 异常: ${t.message}")
        }
    }

    /**
     * 2. 本地决策层：强制将年龄枚举判定为成年人 (档位 4)
     */
    private fun hookLocalAgeCheck(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.bilibili.teenagersmode.model.TeenagersModeAgeCheck",
                classLoader,
                "toIntEnum",
                XC_MethodReplacement.returnConstant(4) // 4 = 成年人档位
            )
            XposedBridge.log("[$TAG] ✅ TeenagersModeAgeCheck 年龄枚举锁定已就绪")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] ❌ AgeCheck Hook 异常: ${t.message}")
        }
    }

    /**
     * 3. 状态快照层：重写本地 TeenagersModeStatus 实例化逻辑
     */
    private fun hookStatusModel(classLoader: ClassLoader) {
        try {
            val statusClass = XposedHelpers.findClassIfExists(
                "com.bilibili.teenagersmode.model.TeenagersModeStatus",
                classLoader
            ) ?: return

            // 拦截构造函数或在返回时确保 status 为 0
            XposedHelpers.findAndHookMethod(
                statusClass,
                "isValid",
                XC_MethodReplacement.returnConstant(true)
            )
            
            XposedBridge.log("[$TAG] ✅ TeenagersModeStatus 状态模型 Hook 已就绪")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] ❌ StatusModel Hook 异常: ${t.message}")
        }
    }
}
