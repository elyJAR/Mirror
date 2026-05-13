# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Kotlin metadata for reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Keep data model classes (used in serialisation / reflection)
-keep class com.antigravity.mirror.model.** { *; }
-keep class com.antigravity.mirror.error.** { *; }
-keep class com.antigravity.mirror.service.MirrorState { *; }

# Kotest (test-only, but keep for debug builds)
-dontwarn io.kotest.**
