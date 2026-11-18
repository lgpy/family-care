package io.github.lgpy.familycare.navigation

/**
 * The routes of the app shell (M2).
 *
 * [topLevel] are the three bottom-bar destinations — Today, Patients and More.
 * The others are pushed screens: More opens the family circle and the user's
 * name, and Patients opens the add and edit forms.
 *
 * Agenda and Shopping arrive in later milestones; they are deliberately absent
 * rather than present as placeholders.
 */
enum class Destination(val route: String) {
    Today("today"),
    Patients("patients"),
    More("more"),
    FamilyCircle("more/family-circle"),
    YourName("more/your-name"),
    PatientNew("patients/new"),
    PatientEdit("patients/{$PATIENT_ID_ARG}"),
    About("about"),
    ;

    companion object {

        /** The bottom-bar destinations, in display order. */
        val topLevel: List<Destination> = listOf(Today, Patients, More)

        /** The route of the edit screen for [patientId]. */
        fun patientEditRoute(patientId: String): String = "patients/$patientId"
    }
}

/** Navigation argument holding the patient being edited. */
const val PATIENT_ID_ARG: String = "patientId"
