# Add project specific ProGuard rules here.

# Keep Hilt generated components
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.** { *; }

# Keep libsu
-keep class com.topjohnwu.superuser.** { *; }

# Keep Shizuku
-keep class rikka.shizuku.** { *; }

# Keep data model classes (used via reflection in DataStore)
-keep class com.merlin.perfmon.data.model.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Compose
-dontwarn androidx.compose.**
