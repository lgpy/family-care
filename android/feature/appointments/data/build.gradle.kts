plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.feature.appointments.data"
}

/*
 * The appointments module's data layer: its repository over the local PowerSync
 * database, and the `appointments` table it contributes to `core:data`.
 *
 * A feature data module may depend on the core and on its own domain, never on
 * another feature (D5, enforced by :checkModuleBoundaries). It is separate from
 * `:feature:appointments:android` so the JVM integration tests can compile these
 * sources without Compose or Android: nothing here imports either.
 */
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":feature:appointments:domain"))

    implementation(libs.kotlinx.coroutines.core)
    // Synced table definitions and the local database the repository reads.
    api(libs.powersync.common)
}
