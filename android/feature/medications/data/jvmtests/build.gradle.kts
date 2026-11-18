plugins {
    id("familycare.kotlin.jvm")
}

/**
 * Integration tests for `:feature:medications:data` on the JVM.
 *
 * This module compiles the medications data sources **and** the real `core:data`
 * sources (minus the two files that need Android) into one plain JVM library, so
 * the tests drive the module's own repositories against a **real PowerSync
 * database** built from the **merged schema** (`CoreSyncedTables` +
 * `MedicationsSyncedTables`), and — when the local Supabase stack is up — send
 * **real PostgREST requests** through the app's own uploader.
 *
 * Why a separate project instead of `:feature:medications:data`'s unit tests: an
 * Android library's unit-test variant resolves the *Android* variants of
 * `com.powersync:common` / `sqlite3multipleciphers`, whose native libraries only
 * exist inside an APK. A Kotlin/JVM project resolves the desktop variants (the
 * published jars carry Linux, macOS and Windows builds), so the same SQL runs
 * here, on a Windows developer machine and on the Ubuntu CI runner.
 *
 * Compiling `core:data`'s sources here (rather than depending on the Android
 * project) is the same approach as `:core:data:jvmtests`: it is what makes the
 * uploader's internal types reachable, and it is why the two excluded files are
 * the only ones in `core:data` that import Android (`DataLayer.kt` and
 * `sync/AndroidDatabaseKey.kt`).
 */
sourceSets {
    main {
        kotlin.srcDir("../src/main/kotlin")
        kotlin.srcDir("../../../../core/data/src/main/kotlin")
        kotlin.exclude(
            "**/core/data/DataLayer.kt",
            "**/core/data/sync/AndroidDatabaseKey.kt",
        )
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":feature:medications:domain"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.core)

    // Desktop PowerSync: `common` carries the JVM engine extension, the
    // `sqlite3multipleciphers` driver carries the JVM SQLite library.
    implementation(libs.powersync.common)
    implementation(libs.powersync.sqlite3multipleciphers)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // supabase-kt needs a Ktor client engine on the JVM; the app supplies the
    // Android engine, so CIO exists only on this module's test classpath.
    testRuntimeOnly(libs.ktor.client.cio)
}
