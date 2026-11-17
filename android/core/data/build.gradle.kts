plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.core.data"
}

dependencies {
    // AuthState is part of this module's public API, so the domain module is an
    // api dependency rather than an implementation detail.
    api(project(":core:domain"))

    // Supabase client (build-plan: "PowerSync + Supabase setup"). PowerSync is a
    // separate, later change: only Auth is used today and Postgrest is installed
    // so the client is already the one the sync layer will use.
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.android)

    testImplementation(libs.junit4)
}
