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
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    // Declares that this build needs a Java 21 toolchain and can provision it,
    // rather than relying on whatever JDK happens to be installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "family-care"

include(":app")

include(":core:domain")
include(":core:data")
include(":core:notifications")
include(":core:ui")

include(":feature:medications:domain")
include(":feature:medications:android")
include(":feature:appointments:domain")
include(":feature:appointments:android")
include(":feature:patient-info:domain")
include(":feature:patient-info:android")
