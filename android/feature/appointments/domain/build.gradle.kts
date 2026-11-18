plugins {
    id("familycare.kotlin.jvm")
}

// Pure Kotlin rules for the appointments feature (D22, D29): no Android, no
// Compose, no DI. Depends on the core's domain for patient identity and on
// nothing else.
dependencies {
    api(project(":core:domain"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
