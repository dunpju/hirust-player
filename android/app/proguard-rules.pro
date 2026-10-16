# HiRust Player R8 规则

# JNI 桥:Rust 侧按 Java_包名_类名_方法名 绑定,类名与 @JvmStatic 方法不得混淆/移除
-keep class com.hirust.player.audio.AudioBridge { *; }
-keep class com.hirust.player.audio.PlayStates { *; }
-keep class com.hirust.player.audio.RepeatModes { *; }
-keep class com.hirust.player.audio.EngineListener { *; }

# Kotlin object 单例(Kotlin metadata 反射按需保留)
-dontwarn kotlinx.coroutines.debug.**

# 三方库的注解类仅编译期使用
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.checker.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
