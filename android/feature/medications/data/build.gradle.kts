plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.feature.medications.data"
}

/*
 * The medications module's data layer: its repositories over the local
 * PowerSync database, and the `meds` tables it contributes to `core:data`.
 *
 * A feature data module may depend on the core and on its own domain, never on
 * another feature (D5, enforced by :checkModuleBoundaries). It is separate from
 * `:feature:medications:android` so the JVM integration tests can compile these
 * sources without Compose or Android: nothing here imports either.
 */
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":feature:medications:domain"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Synced table definitions and the local database the repositories read.
    api(libs.powersync.common)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
