package io.github.lgpy.familycare.navigation

/**
 * The app's navigation shell: Today plus Settings (About lives under Settings),
 * so the navigation graph is exercised by the app itself.
 *
 * The care-circle screens (M2) are not part of the shell yet; they are built on
 * top of `CareCircleRepository.setupState`.
 */
enum class Destination(val route: String) {
    Today("today"),
    Settings("settings"),
    About("about"),
}
