package io.github.lgpy.familycare.navigation

import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.reminders.ReminderRoutes

/**
 * The routes of the app shell (M2, M3).
 *
 * [topLevel] are the four bottom-bar destinations — Today, Patients, Shopping
 * and More. The others are pushed screens: More opens the family circle and the
 * user's name; Patients opens the add and edit forms and, from M3, a patient's
 * medications with their add, detail, edit and schedule screens and the dose
 * slot. Shopping is the M5 list of low medications (D28). The medication routes
 * come from [MedicationsRoutes] so the module owns them and the shell just
 * composes them.
 *
 * Agenda arrives in a later milestone; it is deliberately absent rather than
 * present as a placeholder.
 */
enum class Destination(val route: String) {
    Today("today"),
    Patients("patients"),
    Shopping(MedicationsRoutes.SHOPPING),
    More("more"),
    FamilyCircle("more/family-circle"),
    Reminders(ReminderRoutes.SETUP),
    YourName("more/your-name"),
    PatientNew("patients/new"),
    PatientEdit("patients/{$PATIENT_ID_ARG}"),
    PatientMedications(MedicationsRoutes.PATIENT_MEDICATIONS),
    MedicationNew(MedicationsRoutes.MEDICATION_NEW),
    MedicationDetail(MedicationsRoutes.MEDICATION_DETAIL),
    MedicationEdit(MedicationsRoutes.MEDICATION_EDIT),
    MedicationSchedule(MedicationsRoutes.REGIMEN_CHANGE),
    DoseSlot(MedicationsRoutes.DOSE_SLOT),
    About("about"),
    ;

    companion object {

        /** The bottom-bar destinations, in display order. */
        val topLevel: List<Destination> = listOf(Today, Patients, Shopping, More)

        /** The route of the edit screen for [patientId]. */
        fun patientEditRoute(patientId: String): String = "patients/$patientId"

        /** The route of a patient's medication list. */
        fun patientMedicationsRoute(patientId: String): String =
            MedicationsRoutes.patientMedications(patientId)
    }
}

/** Navigation argument holding the patient being edited. */
const val PATIENT_ID_ARG: String = "patientId"

/** Navigation argument holding the medication being shown or edited. */
const val MEDICATION_ID_ARG: String = MedicationsRoutes.MEDICATION_ID_ARG

/** Navigation argument holding a dose slot's local date and time. */
const val SCHEDULED_LOCAL_ARG: String = MedicationsRoutes.SCHEDULED_LOCAL_ARG
