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
// The data layer's JVM integration tests: real PowerSync database, real
// PostgREST uploads (see the module's build file).
include(":core:data:jvmtests")
include(":core:notifications")
include(":core:ui")

include(":feature:medications:domain")
// The module's data layer (repositories and its synced tables), plus the JVM
// integration tests that drive it against a real PowerSync database and, with
// the local stack up, real PostgREST uploads.
include(":feature:medications:data")
include(":feature:medications:data:jvmtests")
include(":feature:medications:android")
include(":feature:appointments:domain")
// The module's data layer (its repository and its synced table), plus the JVM
// integration tests that drive it against a real PowerSync database.
include(":feature:appointments:data")
include(":feature:appointments:data:jvmtests")
include(":feature:appointments:android")
include(":feature:patient-info:domain")
include(":feature:patient-info:android")
