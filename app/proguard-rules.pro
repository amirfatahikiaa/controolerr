# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

-keep class com.gpmapper.app.service.MappingService { *; }
-keepclassmembers class com.gpmapper.app.service.MappingService {
    native <methods>;
}

-keep class com.gpmapper.app.model.** { *; }
-keep class com.gpmapper.app.input.** { *; }

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

-dontwarn dev.rikka.shizuku.**
-keep class dev.rikka.shizuku.** { *; }
