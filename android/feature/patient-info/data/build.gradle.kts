plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.feature.patientinfo.data"
}

/*
 * The patient-info module's data layer: the repository over the local PowerSync
 * database, and the `info.patient_info` table it contributes to `core:data`.
 *
 * A feature data module may depend on the core and on its own domain, never on
 * another feature (D5, enforced by :checkModuleBoundaries). It is separate from
 * `:feature:patient-info:android` so the JVM integration tests can compile these
 * sources without Compose or Android: nothing here imports either.
 */
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":feature:patient-info:domain"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Synced table definitions and the local database the repository reads.
    api(libs.powersync.common)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
