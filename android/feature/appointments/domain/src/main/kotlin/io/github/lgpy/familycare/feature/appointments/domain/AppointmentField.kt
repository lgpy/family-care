package io.github.lgpy.familycare.feature.appointments.domain

/**
 * One input of the appointment form, named by the rule that rejected it.
 *
 * The server's constraints are mirrored by [Appointment.validate], so a form can
 * mark the offending field instead of showing a constraint error the upload
 * queue could only report as permanent. The core cannot name these fields, which
 * is why the feature owns them (D5).
 */
enum class AppointmentField {
    PATIENT,
    KIND,
    TITLE,
    STARTS_LOCAL,
    TIME_ZONE,
    DURATION,
    PLACE,
    DOCTOR,
    PREPARATION,
    NOTES,
    COMPANION,
    FOLLOW_UP,
}
