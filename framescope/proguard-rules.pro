# The privileged helper is started by class name via app_process.
-keep class com.merlin.framescope.PrivMain {
    public static void main(java.lang.String[]);
}

-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Remove Kotlin null-check intrinsics overhead in release.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull*(...);
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNullExpressionValue(...);
}
