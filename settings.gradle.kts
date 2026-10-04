// settings.gradle.kts — GitHub Actions con Chaquopy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://chaquopy.com/maven") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://chaquopy.com/maven") }
    }
}
rootProject.name = "hdfull-mobile"
include(":app")
