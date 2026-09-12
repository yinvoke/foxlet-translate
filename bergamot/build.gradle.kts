import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.yinvoker.foxlet"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DSSPLIT_USE_INTERNAL_PCRE2=ON",
                    "-DCOMPILE_TESTS=OFF",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    // BUILD_ARCH=native runs *host* SSE probing — poison when
                    // cross-compiling; the explicit arch skips it entirely.
                    "-DBUILD_ARCH=armv8-a",
                )
                targets += "bergamot"
            }
        }
    }

    buildTypes {
        debug {
            // The engine is only useful optimised: a Debug CMake build compiles
            // marian at -O0 -g, which is ~20x slower on device and, because the
            // float loops are no longer vectorised, produces different output
            // bytes than the release .so. The debug *variant* (debuggable
            // Kotlin, debug signing, androidTest) therefore still builds the
            // native library as Release, so instrumentation tests measure and
            // hash the same engine the AAR ships. AGP passes its own
            // -DCMAKE_BUILD_TYPE=Debug first; CMake keeps the last definition.
            externalNativeBuild {
                cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Carry license texts into classes.jar for AAR consumers.
val distributionResources = layout.buildDirectory.dir("generated/distributionResources")
val generateDistributionResources by tasks.registering(Exec::class) {
    outputs.dir(distributionResources)
    // SOURCE.txt records the current commit/tag and local modification state.
    outputs.upToDateWhen { false }
    commandLine("python3", rootProject.file("tools/distribution/package_notices.py"),
        "--resources", distributionResources.get().asFile)
}
androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addStaticSourceDirectory(distributionResources.get().asFile.absolutePath)
    }
}
tasks.named("preBuild") { dependsOn(generateDistributionResources) }

tasks.register<Exec>("packageWithoutPrefixes") {
    dependsOn("assembleRelease")
    commandLine("python3", rootProject.file("tools/distribution/package_notices.py"),
        "--without-prefixes", layout.buildDirectory.file("outputs/aar/bergamot-release.aar").get().asFile,
        "--output", layout.buildDirectory.file("outputs/aar/bergamot-no-prefixes-release.aar").get().asFile)
}
