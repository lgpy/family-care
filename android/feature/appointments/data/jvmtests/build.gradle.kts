plugins {
    id("familycare.kotlin.jvm")
}

/**
 * Integration tests for `:feature:appointments:data` on the JVM.
 *
 * This module compiles the appointments data sources **and** the real `core:data`
 * sources (minus the two files that need Android) into one plain JVM library, so
 * the tests drive the module's repository against a **real PowerSync database**
 * built from the **merged schema** (`CoreSyncedTables` +
 * `AppointmentsSyncedTables`).
 *
 * Why a separate project instead of `:feature:appointments:data`'s unit tests: an
 * Android library's unit-test variant resolves the *Android* variants of
 * `com.powersync:common` / `sqlite3multipleciphers`, whose native libraries only
 * exist inside an APK. A Kotlin/JVM project resolves the desktop variants, so the
 * same SQL runs here, on a Windows developer machine and on the Ubuntu CI runner.
 *
 * No upload test lives here: that would need the local Supabase stack, exactly
 * as the medications module's upload tests do.
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
    implementation(project(":feature:appointments:domain"))

    implementation(libs.kotlinx.coroutines.core)
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
}
