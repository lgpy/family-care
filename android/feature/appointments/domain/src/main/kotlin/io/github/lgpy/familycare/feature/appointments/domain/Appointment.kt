package io.github.lgpy.familycare.feature.appointments.domain

import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One appointment for one patient (docs/domain/appointments.md).
 *
 * **When** is a local wall-clock start plus an IANA zone, the regimens
 * convention (D29): a trip abroad or a DST change never moves the appointment,
 * and "past" is derived in the appointment's own zone. [startInstant] is the
 * absolute instant the two together denote, resolved exactly like a dose slot
 * (`DoseSlotRules`): a wall-clock time in a spring-forward gap moves forward by
 * the length of the gap, and an ambiguous time in a fall-back overlap uses the
 * earlier offset. `LocalDateTime.atZone` is what implements both rules.
 *
 * Cancelling is a timestamp ([cancelledAt]), never a delete, so the history keeps
 * the appointment; edits happen in place (the audit log keeps every change).
 *
 * @param companionUserId The owner/caregiver who takes the patient, or null when
 *   every owner and caregiver is reminded. Membership is checked by the
 *   repository, which can read the circle's memberships.
 * @param followUpOf The earlier appointment this one follows up, if any.
 */
data class Appointment(
    val id: AppointmentId,
    val patientId: PatientId,
    val kind: AppointmentKind,
    val startsLocal: LocalDateTime,
    val timeZone: ZoneId,
    val title: String? = null,
    val durationMinutes: Int? = null,
    val place: String? = null,
    val doctor: String? = null,
    val preparation: String? = null,
    val notes: String? = null,
    val companionUserId: String? = null,
    val followUpOf: AppointmentId? = null,
    val cancelledAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {

    /** Whether the appointment was cancelled; the row itself stays. */
    val isCancelled: Boolean get() = cancelledAt != null

    /** The local date of the start, in the appointment's zone. */
    val localDate: LocalDate get() = startsLocal.toLocalDate()

    /** The local wall-clock time of the start. */
    val localTime: LocalTime get() = startsLocal.toLocalTime()

    /**
     * When the appointment starts in absolute time.
     *
     * `LocalDateTime.atZone` uses the same gap/overlap resolution as
     * `DoseSlotRules`: a non-existent wall-clock time in a spring-forward gap
     * moves forward by the length of the gap, and an ambiguous time in a
     * fall-back overlap takes the earlier offset.
     */
    fun startInstant(): Instant = startsLocal.atZone(timeZone).toInstant()

    /** Past = the start instant is before [now] (a start exactly now is not past). */
    fun isPast(now: Instant): Boolean = startInstant().isBefore(now)

    /**
     * The appointment with its text trimmed (a blank one becomes null) and the
     * start truncated to whole minutes, which is what the server stores.
     */
    fun normalized(): Appointment = copy(
        title = title.normalizedText(),
        place = place.normalizedText(),
        doctor = doctor.normalizedText(),
        preparation = preparation.normalizedText(),
        notes = notes.normalizedText(),
        startsLocal = startsLocal.truncatedTo(ChronoUnit.MINUTES),
    )

    /**
     * Mirrors `appointments.appointments` and its `check_appointment()` trigger:
     * text lengths, the duration range, a known IANA zone and a follow-up that
     * is not this appointment. A companion's membership and a follow-up's
     * patient need a database lookup, so the repository checks those and adds
     * [AppointmentField.COMPANION] / [AppointmentField.FOLLOW_UP].
     */
    fun validate(): AppointmentValidation {
        val errors = mutableSetOf<AppointmentField>()

        if (title != null && title.trim().length > TITLE_MAX_LENGTH) errors += AppointmentField.TITLE
        if (place != null && place.trim().length > PLACE_MAX_LENGTH) errors += AppointmentField.PLACE
        if (doctor != null && doctor.trim().length > DOCTOR_MAX_LENGTH) errors += AppointmentField.DOCTOR
        if (preparation != null && preparation.trim().length > PREPARATION_MAX_LENGTH) {
            errors += AppointmentField.PREPARATION
        }
        if (notes != null && notes.trim().length > NOTES_MAX_LENGTH) errors += AppointmentField.NOTES
        if (durationMinutes != null && durationMinutes !in DURATION_MIN..DURATION_MAX) {
            errors += AppointmentField.DURATION
        }
        if (timeZone.id !in ZoneId.getAvailableZoneIds()) errors += AppointmentField.TIME_ZONE
        if (followUpOf == id) errors += AppointmentField.FOLLOW_UP

        return AppointmentValidation(errors)
    }

    companion object {
        const val TITLE_MAX_LENGTH: Int = 200
        const val PLACE_MAX_LENGTH: Int = 200
        const val DOCTOR_MAX_LENGTH: Int = 200
        const val PREPARATION_MAX_LENGTH: Int = 1000
        const val NOTES_MAX_LENGTH: Int = 2000
        const val DURATION_MIN: Int = 1
        const val DURATION_MAX: Int = 1440
    }
}

/** What [Appointment.validate] found: the inputs to mark in the form. */
data class AppointmentValidation(val errors: Set<AppointmentField>) {

    val isValid: Boolean get() = errors.isEmpty()
}

/** Trimmed text, or null when it is blank: an optional column with no value. */
internal fun String?.normalizedText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
