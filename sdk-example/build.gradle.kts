plugins { id("com.android.application") }

// The instrumentation APK is optimized separately and is invisible to the
// target app's R8 pass. Preserve its SDK call boundaries from the reviewed API
// snapshot while still optimizing/obfuscating the implementation.
val sdkApiSnapshot = rootProject.layout.projectDirectory.file("docs/public-jvm.txt")
val sdkDeviceTestRules = layout.buildDirectory.file("generated/r8/sdk-device-tests.pro")
val generateSdkDeviceTestRules by tasks.registering {
    inputs.file(sdkApiSnapshot)
    outputs.file(sdkDeviceTestRules)
    doLast {
        val publicTypes = Regex("""(?m)^public (?:\w+ )*(?:class|interface) ([\w.$]+)""")
            .findAll(sdkApiSnapshot.asFile.readText())
            .map { it.groupValues[1] }
            .filter { it.startsWith("io.github.yinvoker.foxlet.") }
            .toSortedSet()
        check(publicTypes.isNotEmpty()) { "No public SDK types found in API snapshot" }
        sdkDeviceTestRules.get().asFile.apply {
            parentFile.mkdirs()
            writeText(publicTypes.joinToString("\n", postfix = "\n") {
                "-keep,allowoptimization,allowobfuscation class $it { public *; }"
            })
        }
    }
}

android {
    namespace = "io.github.yinvoker.foxlet.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.yinvoker.foxlet.demo"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = project.version.toString()
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = "release"
    buildTypes {
        release {
            isMinifyEnabled = true
            // Public demonstration only. Use your own signing key for a production app.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", sdkDeviceTestRules)
            testProguardFiles("test-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
// This example consumes the final AAR, not the library's project classes.
val sdkAar = rootProject.layout.projectDirectory.file("foxlet/build/outputs/aar/foxlet-release.aar")
dependencies {
    implementation(files(sdkAar).builtBy(":foxlet:assembleRelease"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
tasks.named("preBuild") { dependsOn(":foxlet:assembleRelease", generateSdkDeviceTestRules) }
