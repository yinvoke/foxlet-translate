import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
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
                    "-DCOMPILE_TESTS=OFF",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    // An explicit target architecture disables host SSE probing during cross-compilation.
                    "-DBUILD_ARCH=armv8-a",
                )
                targets += "foxlet"
            }
        }
    }

    buildTypes {
        debug {
            // Keep native optimization aligned with the release AAR for instrumentation
            // and output regression. Kotlin remains debuggable. AGP supplies Debug first;
            // the final CMAKE_BUILD_TYPE definition selects the native Release build.
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
    // Real org.json for JVM tests: the android.jar stubs throw on every method.
    testImplementation("org.json:json:20240303")

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

dokka {
    moduleName.set("Foxlet Translate")
    dokkaSourceSets.configureEach {
        jdkVersion.set(17)
    }
}

// Keep the Maven group aligned with the GitHub owner; the Kotlin package stays
// io.github.yinvoker.foxlet for API compatibility.
mavenPublishing {
    coordinates("io.github.yinvoke", "foxlet-translate", project.version.toString())
    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
        variant = "release",
        sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
        javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
    ))
    pom {
        name.set("Foxlet Translate")
        description.set("Offline translation SDK for Android, powered by Mozilla models and optimized for ARM. See NOTICE for bundled third-party licenses.")
        url.set("https://github.com/yinvoke/foxlet-translate")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("MIT License (original code)")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
            license {
                name.set("Mozilla Public License 2.0 (Bergamot components)")
                url.set("https://www.mozilla.org/MPL/2.0/")
                distribution.set("repo")
            }

        }
        developers {
            developer {
                id.set("yinvoke")
                name.set("yinvoke")
                url.set("https://github.com/yinvoke")
            }
        }
        scm {
            url.set("https://github.com/yinvoke/foxlet-translate")
            connection.set("scm:git:https://github.com/yinvoke/foxlet-translate.git")
            developerConnection.set("scm:git:ssh://git@github.com/yinvoke/foxlet-translate.git")
        }
    }
    // Local previews need no credentials. Central tasks are enabled explicitly
    // for release preparation; uploading still requires a separate invocation.
    if (providers.gradleProperty("foxlet.publishToCentral").orNull == "true") {
        publishToMavenCentral(automaticRelease = false)
        signAllPublications()
    }
}

publishing {
    repositories {
        maven {
            name = "LocalPreview"
            url = rootProject.layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
        }
    }
}
