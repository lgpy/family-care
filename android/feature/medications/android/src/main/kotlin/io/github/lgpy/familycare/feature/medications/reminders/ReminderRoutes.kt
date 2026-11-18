package io.github.lgpy.familycare.feature.medications.reminders

/**
 * The routes the medications feature owns. The reminders setup screen is part
 * of the app shell's More section, but the reminder health card lives in this
 * module, so both use this constant rather than each spelling the route.
 */
object ReminderRoutes {

    /** More → Reminders. */
    const val SETUP: String = "more/reminders"
}
