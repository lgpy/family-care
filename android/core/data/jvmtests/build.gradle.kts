plugins {
    id("familycare.kotlin.jvm")
}

/**
 * Integration tests for `core:data` on the JVM.
 *
 * This module compiles the real `core:data` sources (except the two files that
 * need Android) into a plain JVM library, so the tests can drive the app's own
 * repositories against a **real PowerSync database** built from the app's
 * **real schema**, and — when the local Supabase stack is up — send **real
 * PostgREST requests** through the app's own uploader.
 *
 * Why a separate project instead of `core:data`'s unit tests: an Android
 * library's unit-test variant resolves the *Android* variants of
 * `com.powersync:common` / `sqlite3multipleciphers`, whose native libraries
 * only exist inside an APK. A Kotlin/JVM project resolves the desktop variants
 * (the published jars carry Linux, macOS and Windows builds of both the SQLite
 * driver and the PowerSync core extension), so the same SQL runs here, on a
 * Windows developer machine and on the Ubuntu CI runner.
 *
 * The two excluded files are the only ones in `core:data` that import Android:
 * `DataLayer.kt` (`Context`, Keystore-backed factory) and
 * `sync/AndroidDatabaseKey.kt` (`AndroidKeyStore`).
 *
 * Neither the database tests nor the upload tests need a device, an emulator
 * or any Android SDK package.
 */
sourceSets {
    main {
        kotlin.srcDir("../src/main/kotlin")
        kotlin.exclude(
            "**/DataLayer.kt",
            "**/sync/AndroidDatabaseKey.kt",
        )
    }
}

dependencies {
    api(project(":core:domain"))

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

    // supabase-kt needs a Ktor client engine on the JVM; the app supplies the
    // Android engine, so CIO exists only on this module's test classpath.
    testRuntimeOnly(libs.ktor.client.cio)
}
