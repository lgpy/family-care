plugins {
    id("familycare.kotlin.jvm")
}

// Pure Kotlin: no Android, no Compose, no DI framework. Only the standard
// library and tests (D5, D22).
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
