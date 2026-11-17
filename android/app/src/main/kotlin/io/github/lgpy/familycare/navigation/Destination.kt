package io.github.lgpy.familycare.navigation

/**
 * The M1 navigation shell: Today plus Settings (About lives under Settings),
 * so the navigation graph is exercised by the app itself.
 *
 * [SyncTest] is the M1 probe screen (build-plan M1 done-test), reachable from
 * Settings under Diagnostics; it goes away in M2 with `core.sync_probe`.
 */
enum class Destination(val route: String) {
    Today("today"),
    Settings("settings"),
    About("about"),
    SyncTest("sync-test"),
}
