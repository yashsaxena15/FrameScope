# Shizuku instantiates the UserService by class name inside a separate (shell-uid)
# process, so its name and constructors must survive R8.
-keep class com.merlin.fpslite.PrivService { <init>(...); *; }
-keep class com.merlin.fpslite.IPrivService { *; }
-keep class com.merlin.fpslite.IPrivService$* { *; }

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
