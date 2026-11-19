package io.github.lgpy.familycare.core.ui.status

/**
 * Status a feature reports for one of the More screen's rows (D5).
 *
 * More is the shell's screen: it may not name a feature module to ask how things
 * are. A feature implements [MoreStatusSource], the app registers it in the
 * dependency graph, and the screen draws whatever it was given — the same
 * extension-point pattern as `DashboardCard` and `PatientTab`.
 */
data class MoreStatus(
    /** How many things on this phone need the user's attention. */
    val problems: Int,
) {

    /** Amber treatment: the row is reporting something to fix. */
    val needsAttention: Boolean get() = problems > 0
}

/**
 * The status lines a feature contributes to More.
 *
 * All methods are best effort: a missing context, an unavailable platform API
 * or a failed check returns null and the screen falls back to its neutral
 * description rather than claiming everything is fine.
 */
interface MoreStatusSource {

    /** The reminder health check: how many issues it found, or null if it could not run. */
    suspend fun reminders(): MoreStatus?
}
