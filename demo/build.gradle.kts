plugins { id("com.android.application") }
android {
    namespace = "io.github.yinvoker.foxlet.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.yinvoker.foxlet.demo"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(files(sdkAar))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
tasks.named("preBuild") { dependsOn(":foxlet:assembleRelease") }
