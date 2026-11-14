pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central; avoids Central's rate limiting.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // JitPack serves only the USB serial driver library; nothing else may resolve from it.
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}
rootProject.name = "HermesAgent"
include(":app")
