# JNI: native code looks these up by name.
-keep class com.offlineagent.engine.llamacpp.LlamaNative { *; }
-keepclasseswithmembernames class * { native <methods>; }
# AIDL binder stubs (cross-app on-device MCP).
-keep class com.offlineagent.mcp.IMcpServer** { *; }
# kotlinx.serialization (macros, profile vault).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.offlineagent.**$$serializer { *; }
-keepclassmembers class com.offlineagent.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
# Room
-keep class * extends androidx.room.RoomDatabase
# PdfBox-Android optional deps
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
