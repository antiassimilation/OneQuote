# Keep WorkManager / Room generated implementation classes for release startup.
# Crash symptom:
# `Unable to get provider androidx.startup.InitializationProvider`
# caused by `NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>[]`

# WorkManager relies on Room-generated implementation classes such as
# `androidx.work.impl.WorkDatabase_Impl` during startup initialization.
-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>();
}

# Keep Room database implementations and their constructors.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class **_Impl {
    <init>();
}

# Keep Room-generated companions used by the implementation.
-keep class **_Impl$* { *; }
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
