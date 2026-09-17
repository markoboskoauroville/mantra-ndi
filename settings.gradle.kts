pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RootEncoder is published through JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "mantra-ndi"
include(":app")
