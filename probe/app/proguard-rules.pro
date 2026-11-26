# R8 rules for the compat APK (release build).

# Keep file and line numbers so stack traces can be decoded with the uploaded mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Xposed entry point, loaded by name from META-INF/xposed/java_init.list. Its callbacks override
# io.github.libxposed.api.XposedModule, which is only on the compile classpath (compileOnly).
-keep class io.github.local.esimh4probe.CompatModule { *; }
# Hook callbacks (lambdas passed to intercept) implement libxposed interfaces; their method names
# must match the API that the framework provides at runtime.
-keepclassmembers class * implements io.github.libxposed.api.** { <methods>; }
-dontwarn io.github.libxposed.api.**

# Command-line entry points started with app_process by the module scripts (Sim2Control) and for
# diagnostics (ProbeInspector, MainActivity).
-keepclasseswithmembers class io.github.local.esimh4probe.** {
    public static void main(java.lang.String[]);
}
