pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "foxlet-translate"
include(":foxlet")
include(":benchmark-app")
// Directory lives under benchmark/; the module path stays :benchmark-app
project(":benchmark-app").projectDir = file("benchmark/app")
include(":sdk-example")
