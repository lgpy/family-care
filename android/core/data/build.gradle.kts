plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.core.data"
}

dependencies {
    // AuthState, SyncState and the M2 core types are part of this module's
    // public API, so the domain module is an api dependency rather than an
    // implementation detail.
    api(project(":core:domain"))

    // Supabase client (build-plan: "PowerSync + Supabase setup"): auth for the
    // session and postgrest for uploads, both sharing one client.
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.android)

    // PowerSync: `common` (no bundled driver/engine) plus the encrypted SQLite
    // driver, so the local file is encrypted at rest (security-and-privacy §4).
    implementation(libs.powersync.common)
    implementation(libs.powersync.sqlite3multipleciphers)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
