pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "Relay"

// Platform-independent engine. Pure Kotlin/JVM so desktop clients reuse it verbatim.
include(":core:protocol")
include(":core:security")
include(":core:discovery")
include(":core:transfer")
include(":core:node")

// Android.
include(":core:designsystem")
include(":core:data")
include(":app")

// Developer tools.
include(":tools:peer")

// Desktop client: Windows, Linux and macOS from one codebase.
include(":desktop")
