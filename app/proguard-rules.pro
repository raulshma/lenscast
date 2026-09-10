# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# CameraX — ships its own consumer rules; a broad manual keep here would
# only block R8 from shrinking unused CameraX code.
-dontwarn androidx.camera.**

# NanoHTTPD — plain direct-call library, no reflection on user classes;
# R8 keeps everything transitively reachable from the service entry points.
-dontwarn fi.iki.elonen.**

# Moshi — ships its own consumer rules; only the kotlin-reflect keeps below
# (needed by KotlinJsonAdapterFactory) are app-supplied.
-keep @com.squareup.moshi.JsonQualifier interface *
-keepclassmembers @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
    **[] values();
}

# Keep ViewModel
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }

# Keep Service classes
-keep class * extends android.app.Service { *; }

# JSON serialization
-keepclassmembers class * {
    @org.json.** *;
}

# AndroidX Lifecycle
-keep class * implements androidx.lifecycle.GeneratedAdapter { <init>(...); }
-keep class * extends androidx.lifecycle.LifecycleObserver { <init>(...); }

# Navigation
-keep class * extends androidx.navigation.Navigator { <init>(...); }

# Preserve annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelize
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Moshi codegen: adapters are resolved reflectively by class name ──
-keepnames @com.squareup.moshi.JsonClass class *
-keepnames class *JsonAdapter
-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses

# ── App model classes: Compose state, Moshi DTOs, enums ──
-keep class com.raulshma.lenscast.gallery.GalleryFilter { *; }

# ── WorkManager / Room (WorkDatabase_Impl) ──
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.impl.WorkDatabase { *; }
-dontwarn androidx.work.impl.WorkDatabase_Impl

# MediaPipe Tasks (ML object-detection gate). The AARs ship no consumer
# rules: the native JNI layer resolves these classes and their members by
# name when marshaling results, so shrinking/renaming breaks it at runtime.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
# Guava (via MediaPipe tasks-core) references compile-only annotations.
-dontwarn com.google.errorprone.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.**
-dontwarn com.google.auto.value.**
-dontwarn auto.value.**

# ── Enum safety (valueOf / values) ──
-keepclassmembers enum * {
    **[] values();
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
