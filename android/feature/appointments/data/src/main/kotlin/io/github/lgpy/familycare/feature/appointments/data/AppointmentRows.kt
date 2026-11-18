package io.github.lgpy.familycare.feature.appointments.data

import com.powersync.db.SqlCursor
import com.powersync.db.getString
import com.powersync.db.getStringOptional
import com.powersync.db.getLongOptional
import io.github.lgpy.familycare.core.data.circle.parseServerInstant
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentId
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.toJavaInstant

/**
 * One joined row of `appointments` + `patients`, as it comes out of SQLite,
 * before it becomes a domain [Appointment].
 *
 * Kept separate from the cursor reading so the interesting part — enum names,
 * zones, local date-times — is unit-tested without a PowerSync database.
 * A row with a kind, zone or start this app cannot read is skipped: showing a
 * broken appointment is worse than not showing it.
 */
internal data class AppointmentRow(
    val id: String,
    val patientId: String,
    val kind: String,
    val title: String?,
    val startsLocal: String,
    val timeZone: String,
    val durationMinutes: Long?,
    val place: String?,
    val doctor: String?,
    val preparation: String?,
    val notes: String?,
    val companionUserId: String?,
    val followUpOf: String?,
    val cancelledAt: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val patientName: String,
) {

    /** The domain appointment, or null when a value this app cannot read is present. */
    fun toAppointment(): Appointment? {
        val appointmentKind = AppointmentKind.fromServer(kind) ?: return null
        val zone = runCatching { ZoneId.of(timeZone) }.getOrNull() ?: return null
        val start = parseStartsLocal(startsLocal) ?: return null

        return Appointment(
            id = AppointmentId(id),
            patientId = PatientId(patientId),
            kind = appointmentKind,
            startsLocal = start,
            timeZone = zone,
            title = title,
            durationMinutes = durationMinutes?.toInt(),
            place = place,
            doctor = doctor,
            preparation = preparation,
            notes = notes,
            companionUserId = companionUserId,
            followUpOf = followUpOf?.let { AppointmentId(it) },
            cancelledAt = parseServerInstant(cancelledAt)?.toJavaInstant(),
            createdAt = parseServerInstant(createdAt)?.toJavaInstant(),
            updatedAt = parseServerInstant(updatedAt)?.toJavaInstant(),
        )
    }
}

/** Reads one joined row of any `SELECT_*APPOINTMENT*` query. */
internal fun SqlCursor.toAppointmentRow(): AppointmentRow = AppointmentRow(
    id = getString("id"),
    patientId = getString("patient_id"),
    kind = getString("kind"),
    title = getStringOptional("title"),
    startsLocal = getString("starts_local"),
    timeZone = getString("time_zone"),
    durationMinutes = getLongOptional("duration_minutes"),
    place = getStringOptional("place"),
    doctor = getStringOptional("doctor"),
    preparation = getStringOptional("preparation"),
    notes = getStringOptional("notes"),
    companionUserId = getStringOptional("companion_user_id"),
    followUpOf = getStringOptional("follow_up_of"),
    cancelledAt = getStringOptional("cancelled_at"),
    createdAt = getStringOptional("created_at"),
    updatedAt = getStringOptional("updated_at"),
    patientName = getString("patient_display_name"),
)

/** One appointment with the patient's display name, for the cross-patient agenda. */
data class AppointmentEntry(
    val appointment: Appointment,
    val patientName: String,
)

/** Rows to domain entries, dropping anything this app cannot read. */
internal fun List<AppointmentRow>.toEntries(): List<AppointmentEntry> = mapNotNull { row ->
    row.toAppointment()?.let { AppointmentEntry(appointment = it, patientName = row.patientName) }
}

/** Rows to domain appointments, dropping anything this app cannot read. */
internal fun List<AppointmentRow>.toAppointments(): List<Appointment> =
    mapNotNull { it.toAppointment() }

/*
 * The text forms below are the contract with the server. Each value is written
 * locally in the form Postgres would send it back in, so a row that has
 * round-tripped through sync is identical to one written on this phone.
 */

/**
 * `timestamp without time zone` → `YYYY-MM-DD HH:MM:SS`, the form Postgres uses
 * in its text output (a space, not `T`).
 */
internal fun formatStartsLocal(value: LocalDateTime): String =
    value.format(STARTS_LOCAL_FORMAT)

/**
 * The inverse of [formatStartsLocal].
 *
 * Accepts the space form that comes from Postgres, an ISO `T` form, fractional
 * seconds and a trailing `Z`/offset, and truncates to the minute (the UI picks
 * `HH:MM`, and the server stores whole minutes). A value that is still not a
 * local date-time is null: a malformed row must not break a screen.
 */
internal fun parseStartsLocal(value: String?): LocalDateTime? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val iso = text.replace(' ', 'T')

    runCatching { LocalDateTime.parse(iso) }.getOrNull()?.let { return it.toMinute() }

    val offset = runCatching { java.time.OffsetDateTime.parse(iso) }.getOrNull()
    if (offset != null) return offset.toLocalDateTime().toMinute()

    val withoutZone = iso.removeSuffix("Z")
    return runCatching { LocalDateTime.parse(withoutZone) }.getOrNull()?.toMinute()
}

private fun LocalDateTime.toMinute(): LocalDateTime =
    if (second == 0 && nano == 0) this else truncatedTo(ChronoUnit.MINUTES)

private val STARTS_LOCAL_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/** Parameters of [INSERT_APPOINTMENT], in column order. */
internal fun appointmentParameters(appointment: Appointment): List<Any?> = listOf(
    appointment.id.value,
    appointment.patientId.value,
    appointment.kind.serverValue,
    appointment.title,
    formatStartsLocal(appointment.startsLocal),
    appointment.timeZone.id,
    appointment.durationMinutes,
    appointment.place,
    appointment.doctor,
    appointment.preparation,
    appointment.notes,
    appointment.companionUserId,
    appointment.followUpOf?.value,
)

/**
 * Parameters of [UPDATE_APPOINTMENT]: the insert's, less id and patient, plus
 * `cancelled_at` and the filter id.
 */
internal fun appointmentUpdateParameters(appointment: Appointment): List<Any?> =
    appointmentParameters(appointment).drop(2) +
        appointment.cancelledAt?.toString() +
        appointment.id.value
