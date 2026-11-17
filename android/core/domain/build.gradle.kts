plugins {
    id("familycare.kotlin.jvm")
}

// Pure Kotlin: no Android, no Compose, no DI framework. Only the standard
// library, kotlinx-coroutines (for `Flow`, which the repository contracts
// return) and tests (D5, D22).
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
