# 🎯 核心指令：禁止混淆重命名任何类名和方法名，保持 100% 原汁原味明文！
-dontobfuscate

# 保留源代码行号与调试属性，报错时堆栈信息完全可读
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,EnclosingMethod

# 保护所有本模块类和现代 LibXposed 核心
-keep class me.bili.unrestrict.** { *; }
-keep class io.github.libxposed.** { *; }

# 保护 Room 数据库与 Kotlin 序列化
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    *** Companion;
}

# 忽略第三方库无害警告
-dontwarn okhttp3.**
-dontwarn okio.**
