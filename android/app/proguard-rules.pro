# JNA 按名字反射找 Java 侧的结构体、回调与 Native 方法，类名和成员都不能动
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# UniFFI 生成的绑定：JNA Library 接口的方法名就是 .so 里的符号名，结构体字段按声明顺序映射
-keep class uniffi.** { *; }

# sherpa-onnx native code accesses Kotlin config/result fields and constructors
# by name. Its official AAR currently supplies no consumer keep rules.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# tomlj 的 ANTLR 解析器
-dontwarn org.antlr.**
-dontwarn org.checkerframework.**
