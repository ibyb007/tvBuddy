# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# FFmpeg audio decoder is instantiated by reflection from DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# ---- tvBuddy release (R8) ----
# Names are not obfuscated: the saving comes from removing unused code, and readable crash traces are worth more.
-dontobfuscate

# Software audio decoder (E-AC3 / AC3 / DTS / TrueHD): created by name via reflection and called from native code
-keep class androidx.media3.decoder.** { *; }
-keep class org.jellyfin.media3.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ExoPlayer looks up optional renderers by name
-dontwarn androidx.media3.decoder.**
-dontwarn org.jellyfin.media3.**
