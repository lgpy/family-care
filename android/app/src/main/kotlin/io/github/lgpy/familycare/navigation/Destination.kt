package io.github.lgpy.familycare.navigation

/**
 * The M1 navigation shell: Today plus one placeholder destination, so the
 * navigation graph is exercised by the app itself.
 */
enum class Destination(val route: String) {
    Today("today"),
    About("about"),
}
