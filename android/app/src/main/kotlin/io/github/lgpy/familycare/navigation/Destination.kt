package io.github.lgpy.familycare.navigation

/**
 * The M1 navigation shell: Today plus Settings (About lives under Settings),
 * so the navigation graph is exercised by the app itself.
 */
enum class Destination(val route: String) {
    Today("today"),
    Settings("settings"),
    About("about"),
}
