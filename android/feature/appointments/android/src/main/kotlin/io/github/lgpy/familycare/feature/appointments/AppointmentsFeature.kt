package io.github.lgpy.familycare.feature.appointments

import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.LocalDateTime

/**
 * Entry point of the appointments feature module (docs/domain/appointments.md,
 * D29).
 *
 * The module owns its screens and the routes that address them; the app shell
 * composes both into its navigation graph and registers
 * [io.github.lgpy.familycare.feature.appointments.profile.AppointmentsPatientTab]
 * as a `PatientTab`. Nothing here imports the app, and the app imports only this
 * object and the screen entry points it wires.
 */
object AppointmentsFeature {
    const val ID: String = "appointments"
}

/**
 * The routes of the appointments screens.
 *
 * The app's `Destination` uses these patterns and helpers, so the module and the
 * shell cannot drift apart. The Agenda is a top-level destination owned by the
 * shell (`Destination.Agenda`), which uses [AGENDA] as its route so a reminder
 * (chunk 2) can open the list without naming the shell.
 */
object AppointmentsRoutes {

    /** The app's Agenda tab: upcoming appointments across every patient. */
    const val AGENDA: String = "agenda"

    /** Navigation argument holding the patient a new appointment is for. */
    const val PATIENT_ID_ARG: String = "patientId"

    /** Navigation argument holding the appointment being shown or edited. */
    const val APPOINTMENT_ID_ARG: String = "appointmentId"

    /**
     * The new-appointment form. The patient is a **query** argument with an
     * empty default: from the Agenda the form shows a patient picker, from a
     * patient's tab the patient is fixed.
     */
    const val APPOINTMENT_NEW: String = "appointments/new?$PATIENT_ID_ARG={$PATIENT_ID_ARG}"

    const val APPOINTMENT_DETAIL: String = "appointments/{$APPOINTMENT_ID_ARG}"
    const val APPOINTMENT_EDIT: String = "appointments/{$APPOINTMENT_ID_ARG}/edit"
    const val APPOINTMENT_FOLLOW_UP: String = "appointments/{$APPOINTMENT_ID_ARG}/follow-up"

    /** The route of the new-appointment form, for [patientId] or (null) for any. */
    fun newAppointment(patientId: String? = null): String =
        "appointments/new?$PATIENT_ID_ARG=${patientId.orEmpty()}"

    /** The route of one appointment's detail screen. */
    fun appointmentDetail(appointmentId: String): String = "appointments/$appointmentId"

    /** The route of the "edit appointment" screen. */
    fun editAppointment(appointmentId: String): String = "appointments/$appointmentId/edit"

    /** The route of the follow-up form, pre-filled from [appointmentId]. */
    fun followUp(appointmentId: String): String = "appointments/$appointmentId/follow-up"

    /** [newAppointment] for a typed id. */
    fun newAppointment(patientId: PatientId): String = newAppointment(patientId.value)

    /**
     * The start time of an appointment from its stored fields, used by tests and
     * by nothing in the UI; kept here so a malformed route value is caught in one
     * place.
     */
    fun startsLocal(date: java.time.LocalDate, time: java.time.LocalTime): LocalDateTime =
        LocalDateTime.of(date, time)
}
