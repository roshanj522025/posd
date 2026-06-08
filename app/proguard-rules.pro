# ProotDroid ProGuard Rules
# Keep proot-related classes
-keep class com.prootdroid.proot.** { *; }
-keep class com.prootdroid.vnc.** { *; }
-keep class com.prootdroid.terminal.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
