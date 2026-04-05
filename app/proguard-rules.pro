# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonQualifier interface *
-keepclassmembers @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
    **[] values();
}
-keepclassmembers class com.squareup.moshi.** {
    <init>(...);
}

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# DataStore
-keep class androidx.datastore.** { *; }

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

# ── Kotlin reflection (needed by Moshi KotlinJsonAdapterFactory) ──
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses

# ── Moshi Kotlin Adapter Factory ──
-keep class com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory { *; }
-keep class com.squareup.moshi.kotlin.reflect.** { *; }
-dontwarn com.squareup.moshi.kotlin.reflect.**
-dontwarn kotlin.reflect.jvm.internal.**

# ── App model classes: Compose state, Moshi DTOs, enums ──
-keep class com.raulshma.lenscast.camera.model.** { *; }
-keep class com.raulshma.lenscast.capture.model.** { *; }
-keep class com.raulshma.lenscast.streaming.model.** { *; }
-keep class com.raulshma.lenscast.core.** { *; }
-keep class com.raulshma.lenscast.data.** { *; }
-keep class com.raulshma.lenscast.gallery.GalleryFilter { *; }

# ── WorkManager / Room (WorkDatabase_Impl) ──
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.impl.WorkDatabase { *; }
-dontwarn androidx.work.impl.WorkDatabase_Impl

# ── Enum safety (valueOf / values) ──
-keepclassmembers enum * {
    **[] values();
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
