# The separate instrumentation APK contains Kotlin lambdas extending this class.
# Keep its runtime base in the target app even if app lambdas are optimized away.
-keep class kotlin.jvm.internal.Lambda { *; }
# SDK call boundaries used by the separate device-test APK are generated from
# api/public-jvm.txt by generateSdkDeviceTestRules in build.gradle.kts.
# AndroidX Test and Kotlin test code run from a separate APK and share the target
# app's Kotlin runtime. Their references are invisible to the app's R8 pass.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
