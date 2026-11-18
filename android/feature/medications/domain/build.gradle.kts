plugins {
    id("familycare.kotlin.jvm")
}

// Pure Kotlin rules for the medications feature (D22): no Android, no
// Compose, no DI. Depends on the core's domain for patient identity and the
// reminder policy, and on nothing else.
dependencies {
    api(project(":core:domain"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Reads the shared test vectors (shared/test-vectors, D22). Only the JSON
    // reader is used, so the serialization compiler plugin is not needed.
    testImplementation(libs.kotlinx.serialization.json)
}

// The language-neutral vectors live at the repository root, outside the
// Gradle build. Putting the directory on the test resources classpath lets a
// test read `dose-slots.json` / `dose-status.json` by name, both locally and in
// CI (which runs `./gradlew build` from android/).
sourceSets {
    test {
        resources.srcDir(rootProject.file("../shared/test-vectors"))
    }
}
