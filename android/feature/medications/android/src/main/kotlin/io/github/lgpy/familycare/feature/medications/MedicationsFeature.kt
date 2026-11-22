package io.github.lgpy.familycare.feature.medications

/**
 * Entry point of the medications feature module (docs/build-plan.md §M3).
 *
 * The module owns its screens and the routes that address them; the app shell
 * composes both into its navigation graph and registers
 * [io.github.lgpy.familycare.feature.medications.dashboard.MedicationsDashboardCard]
 * as a `DashboardCard`. Nothing here imports the app, and the app imports only
 * this object and the screen entry points it wires.
 */
object MedicationsFeature {
    const val ID: String = "medications"
}

/**
 * The routes of the medications screens.
 *
 * The app's `Destination` uses these patterns and helpers, so the module and the
 * shell cannot drift apart. A dose slot is addressed by its wall-clock identity
 * (`patientId`, local date and time) because that, not an instant, is what the
 * dose log stores (docs/domain/medications.md "Dose slots").
 *
 * The scheduled time is encoded as `yyyy-MM-dd_HHmm` rather than ISO-8601: a
 * path segment with `:` is fragile once a route is also used as a deep link.
 */
object MedicationsRoutes {

    /**
     * The app's Shopping tab. The module exposes the route instead of naming the
     * shell's `Destination`, so a card or a notification can open the shopping
     * list without either side importing the other (D5).
     */
    const val SHOPPING: String = "shopping"

    /** The doses of the last days nobody confirmed, to catch up on. */
    const val MISSED_DOSES: String = "doses/missed"

    /** Navigation argument holding the patient a medication belongs to. */
    const val PATIENT_ID_ARG: String = "patientId"

    /** Navigation argument holding the medication being shown or edited. */
    const val MEDICATION_ID_ARG: String = "medicationId"

    /** Navigation argument holding a dose slot's local date and time. */
    const val SCHEDULED_LOCAL_ARG: String = "scheduledLocal"

    const val PATIENT_MEDICATIONS: String = "patients/{$PATIENT_ID_ARG}/medications"
    const val MEDICATION_NEW: String = "patients/{$PATIENT_ID_ARG}/medications/new"
    const val MEDICATION_DETAIL: String =
        "patients/{$PATIENT_ID_ARG}/medications/{$MEDICATION_ID_ARG}"
    const val MEDICATION_EDIT: String =
        "patients/{$PATIENT_ID_ARG}/medications/{$MEDICATION_ID_ARG}/edit"
    const val REGIMEN_CHANGE: String =
        "patients/{$PATIENT_ID_ARG}/medications/{$MEDICATION_ID_ARG}/schedule"
    const val DOSE_SLOT: String =
        "patients/{$PATIENT_ID_ARG}/doses/{$SCHEDULED_LOCAL_ARG}"

    /** The route of a patient's medication list. */
    fun patientMedications(patientId: String): String = "patients/$patientId/medications"

    /** The route of the add-medication flow for [patientId]. */
    fun newMedication(patientId: String): String = "patients/$patientId/medications/new"

    /** The route of one medication's detail screen. */
    fun medicationDetail(patientId: String, medicationId: String): String =
        "patients/$patientId/medications/$medicationId"

    /** The route of the "edit details" screen of one medication. */
    fun editMedication(patientId: String, medicationId: String): String =
        "patients/$patientId/medications/$medicationId/edit"

    /** The route of the "change dose or schedule" screen of one medication. */
    fun changeRegimen(patientId: String, medicationId: String): String =
        "patients/$patientId/medications/$medicationId/schedule"

    /** The route of one dose slot of [patientId] at [scheduledLocal]. */
    fun doseSlot(patientId: String, scheduledLocal: java.time.LocalDateTime): String =
        "patients/$patientId/doses/${encodeScheduledLocal(scheduledLocal)}"

    /** `yyyy-MM-dd_HHmm`, the form [DOSE_SLOT] carries. */
    fun encodeScheduledLocal(scheduledLocal: java.time.LocalDateTime): String =
        scheduledLocal.toLocalDate().toString() +
            "_" +
            scheduledLocal.toLocalTime().hour.toString().padStart(2, '0') +
            scheduledLocal.toLocalTime().minute.toString().padStart(2, '0')

    /** The inverse of [encodeScheduledLocal]; null when [value] is malformed. */
    fun decodeScheduledLocal(value: String?): java.time.LocalDateTime? {
        val parts = value?.split('_') ?: return null
        if (parts.size != 2 || parts[1].length != 4) return null
        val date = runCatching { java.time.LocalDate.parse(parts[0]) }.getOrNull() ?: return null
        val hour = parts[1].take(2).toIntOrNull() ?: return null
        val minute = parts[1].takeLast(2).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return date.atTime(hour, minute)
    }
}
