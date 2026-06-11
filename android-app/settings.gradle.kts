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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CoatiCheck"
include(":app")
include(":core:common")
include(":core:database")
include(":core:network")
include(":core:datastore")
include(":core:sync")
include(":core:security")
include(":core:ui")
include(":feature:device-auth")
include(":feature:employee-enrollment")
include(":feature:face-recognition")
include(":feature:attendance")
include(":feature:location")
include(":feature:settings")
