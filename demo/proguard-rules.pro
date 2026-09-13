# The separate instrumentation APK contains Kotlin lambdas extending this class.
# Keep its runtime base in the target app even if app lambdas are optimized away.
-keep class kotlin.jvm.internal.Lambda { *; }
# These public SDK entry points are also called by the separate device-test APK.
# This keeps that consumer API reachable; internal SDK code remains optimized.
-keep,allowoptimization,allowobfuscation class io.github.yinvoker.foxlet.FoxletEngine { public *; }
-keep,allowoptimization,allowobfuscation class io.github.yinvoker.foxlet.ModelFiles { public *; }
-keep,allowoptimization,allowobfuscation class io.github.yinvoker.foxlet.ModelCatalog { public *; }
# The nested value types (Model, InstalledModel, UpdateReport, ...) are read by the test APK too.
-keep,allowoptimization,allowobfuscation class io.github.yinvoker.foxlet.ModelCatalog$* { public *; }
# AndroidX Test and Kotlin test code run from a separate APK and share the target
# app's Kotlin runtime. Their references are invisible to the app's R8 pass.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
