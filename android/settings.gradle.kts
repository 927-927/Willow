// Willow's Android build. Like GradleRIO in FRC: settings.gradle.kts says where plugins and
// libraries come from, app/build.gradle.kts says how to build the app.
// The Aliyun mirrors come first because the line here is slow; Google/Maven Central are the fallback.
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "willow"
// The probe (android/probe) still builds with build-offline.sh; it needs no libraries.
include(":app")
