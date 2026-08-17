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
    }
}

rootProject.name = "hype-car"

include(
    ":app",
    ":baselineprofile",
    ":core:model",
    ":core:network",
    ":core:data",
    ":core:playback",
    ":core:ui",
    ":feature:auth",
    ":feature:catalog",
    ":feature:library",
    ":feature:search",
    ":feature:details",
    ":feature:player",
    ":auto",
)
