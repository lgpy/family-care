package io.github.lgpy.familycare.feature.patientinfo

/**
 * Entry point of the patient-info feature module (docs/domain/patient-info.md,
 * D29).
 *
 * The module owns its screens and the routes that address them; the app shell
 * composes its routes into the navigation graph and registers
 * [io.github.lgpy.familycare.feature.patientinfo.profile.AllergiesPatientHeader]
 * as a `PatientHeader` and
 * [io.github.lgpy.familycare.feature.patientinfo.profile.InfoPatientTab] as a
 * `PatientTab`. Nothing here imports the app (D5).
 */
object PatientInfoFeature {
    const val ID: String = "patient-info"
}

/**
 * The routes of the patient-info screens.
 *
 * The app's navigation graph composes these patterns, so the module and the
 * shell cannot drift apart. Only one screen has a route of its own: the Info tab
 * and the allergies banner live inside the profile, and an edit opens this
 * screen through the profile's `onNavigate` callback.
 */
object PatientInfoRoutes {

    /** Navigation argument holding the patient whose info is edited. */
    const val PATIENT_ID_ARG: String = "patientId"

    /** The edit form of a patient's info. */
    const val EDIT: String = "patients/{$PATIENT_ID_ARG}/info/edit"

    /** The route of [patientId]'s info edit form. */
    fun edit(patientId: String): String = "patients/$patientId/info/edit"
}
