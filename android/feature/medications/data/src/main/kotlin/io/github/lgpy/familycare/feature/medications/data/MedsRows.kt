package io.github.lgpy.familycare.feature.medications.data

import com.powersync.db.SqlCursor
import com.powersync.db.getString
import com.powersync.db.getStringOptional
import com.powersync.db.getLong
import com.powersync.db.getLongOptional
import io.github.lgpy.familycare.core.data.circle.parseServerInstant
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntryId
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.QuantityRules
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.toJavaInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One joined row of `medications` + `patients` + `regimens`, as it comes out of
 * SQLite, before it becomes a domain [Medication] and [Regimen].
 *
 * Kept separate from the cursor reading so the interesting part — enum names,
 * dates, the `times` JSON, quantities — is unit-tested without a PowerSync
 * database.
 */
internal data class MedicationRow(
    val id: String,
    val patientId: String,
    val name: String,
    val activeIngredient: String?,
    val strength: String?,
    val form: String,
    val stockUnit: String,
    val instructions: String?,
    val requiresPrescription: Long,
    val prescriptionValidUntil: String?,
    val lowStockThresholdDays: Long,
    val reminderPolicy: String?,
    val primaryCaregiverId: String?,
    val stoppedAt: String?,
    val patientName: String,
    val patientEscalationDelayMinutes: Long,
    val patientMissedWindowMinutes: Long,
    val regimen: RegimenRow?,
) {

    /**
     * The domain medication, or null when the row holds a form or policy this
     * app does not know (a server enum value newer than the app) or a date it
     * cannot read. Skipping the row beats crashing or showing a broken
     * medication.
     */
    fun toMedication(): Medication? {
        val medicationForm = MedicationForm.fromServer(form) ?: return null
        val policy = when (val value = reminderPolicy) {
            null -> null
            else -> ReminderPolicy.fromServer(value) ?: return null
        }
        val validUntil = when (val value = prescriptionValidUntil) {
            null -> null
            else -> parseDate(value) ?: return null
        }

        return Medication(
            id = MedicationId(id),
            patientId = PatientId(patientId),
            name = name,
            activeIngredient = activeIngredient,
            strength = strength,
            form = medicationForm,
            stockUnit = stockUnit,
            instructions = instructions,
            requiresPrescription = requiresPrescription != 0L,
            prescriptionValidUntil = validUntil,
            lowStockThresholdDays = lowStockThresholdDays.toInt(),
            reminderPolicy = policy,
            primaryCaregiverId = primaryCaregiverId,
            stoppedAt = parseServerInstant(stoppedAt)?.toJavaInstant(),
        )
    }

    /** The settings dose status needs, read from the patient row of the join. */
    fun toPatient(): PatientDoseSettings = PatientDoseSettings(
        patientId = PatientId(patientId),
        displayName = patientName,
        escalationDelayMinutes = patientEscalationDelayMinutes.toInt(),
        missedWindowMinutes = patientMissedWindowMinutes.toInt(),
    )
}

/**
 * One `regimens` row, before it becomes a domain [Regimen]. A row with an
 * unknown schedule, zone or malformed `times` is skipped by [toRegimen]: its
 * slots cannot be computed, so it must not look like an active schedule.
 */
internal data class RegimenRow(
    val id: String,
    val medicationId: String,
    val scheduleType: String,
    val weekdays: Long?,
    val intervalDays: Long?,
    val startDate: String,
    val endDate: String?,
    val timeZone: String,
    val times: String,
) {

    fun toRegimen(): Regimen? {
        val schedule = ScheduleType.fromServer(scheduleType) ?: return null
        val zone = runCatching { ZoneId.of(timeZone) }.getOrNull() ?: return null
        val start = parseDate(startDate) ?: return null
        val end = when (val value = endDate) {
            null -> null
            else -> parseDate(value) ?: return null
        }
        val doseTimes = parseTimes(times) ?: return null

        return Regimen(
            id = RegimenId(id),
            medicationId = MedicationId(medicationId),
            scheduleType = schedule,
            startDate = start,
            timeZone = zone,
            times = doseTimes,
            endDate = end,
            weekdays = weekdays?.toInt(),
            intervalDays = intervalDays?.toInt(),
        )
    }
}

/** Reads one joined row of any `SELECT_*_MEDICATIONS` query. */
internal fun SqlCursor.toMedicationRow(): MedicationRow {
    val regimenId = getStringOptional("regimen_id")

    return MedicationRow(
        id = getString("id"),
        patientId = getString("patient_id"),
        name = getString("name"),
        activeIngredient = getStringOptional("active_ingredient"),
        strength = getStringOptional("strength"),
        form = getString("form"),
        stockUnit = getString("stock_unit"),
        instructions = getStringOptional("instructions"),
        requiresPrescription = getLong("requires_prescription"),
        prescriptionValidUntil = getStringOptional("prescription_valid_until"),
        lowStockThresholdDays = getLong("low_stock_threshold_days"),
        reminderPolicy = getStringOptional("reminder_policy"),
        primaryCaregiverId = getStringOptional("primary_caregiver_id"),
        stoppedAt = getStringOptional("stopped_at"),
        patientName = getString("patient_display_name"),
        patientEscalationDelayMinutes = getLong("escalation_delay_minutes"),
        patientMissedWindowMinutes = getLong("missed_window_minutes"),
        regimen = if (regimenId == null) {
            null
        } else {
            RegimenRow(
                id = regimenId,
                medicationId = getString("regimen_medication_id"),
                scheduleType = getString("schedule_type"),
                weekdays = getLongOptional("weekdays"),
                intervalDays = getLongOptional("interval_days"),
                startDate = getString("start_date"),
                endDate = getStringOptional("end_date"),
                timeZone = getString("time_zone"),
                times = getString("times"),
            )
        },
    )
}

/**
 * Groups the joined rows into one [MedicationWithRegimens] per medication,
 * keeping the query's order.
 *
 * The join duplicates the medication for every regimen version; a medication
 * whose own columns cannot be read is dropped whole, while one unreadable
 * regimen version only loses that version.
 */
internal fun List<MedicationRow>.toMedicationWithRegimens(): List<MedicationWithRegimens> =
    groupBy { it.id }.values.mapNotNull { rows ->
        val first = rows.first()
        val medication = first.toMedication() ?: return@mapNotNull null

        MedicationWithRegimens(
            medication = medication,
            regimens = rows.mapNotNull { it.regimen?.toRegimen() },
            patient = first.toPatient(),
        )
    }

/** One `dose_log` row, before it becomes a domain [DoseLogEntry]. */
internal data class DoseLogRow(
    val id: String,
    val regimenId: String,
    val medicationId: String,
    val scheduledLocal: String,
    val outcome: String,
    val skipReason: String?,
    val note: String?,
    val actualAt: String,
    val actualQuantity: String?,
    val confirmedBy: String,
    val givenByUserId: String?,
    val givenByOther: Long,
    val givenByName: String?,
    val correctsEntryId: String?,
) {

    /** The domain entry, or null when a value this app cannot read is present. */
    fun toEntry(): DoseLogEntry? {
        val scheduled = parseScheduledLocal(scheduledLocal) ?: return null
        val doseOutcome = DoseOutcome.fromServer(outcome) ?: return null
        val reason = when (val value = skipReason) {
            null -> null
            else -> SkipReason.fromServer(value) ?: return null
        }
        val at = parseServerInstant(actualAt)?.toJavaInstant() ?: return null
        val quantity = when (val value = actualQuantity) {
            null -> null
            else -> parseQuantity(value) ?: return null
        }

        return DoseLogEntry(
            id = DoseLogEntryId(id),
            regimenId = RegimenId(regimenId),
            medicationId = MedicationId(medicationId),
            scheduledLocal = scheduled,
            outcome = doseOutcome,
            actualAt = at,
            confirmedBy = confirmedBy,
            skipReason = reason,
            note = note,
            actualQuantity = quantity,
            givenByUserId = givenByUserId,
            givenByOther = givenByOther != 0L,
            givenByName = givenByName,
            correctsEntryId = correctsEntryId?.let { DoseLogEntryId(it) },
        )
    }
}

/** Reads one row of any `SELECT_*DOSE_LOG*` query. */
internal fun SqlCursor.toDoseLogRow(): DoseLogRow = DoseLogRow(
    id = getString("id"),
    regimenId = getString("regimen_id"),
    medicationId = getString("medication_id"),
    scheduledLocal = getString("scheduled_local"),
    outcome = getString("outcome"),
    skipReason = getStringOptional("skip_reason"),
    note = getStringOptional("note"),
    actualAt = getString("actual_at"),
    actualQuantity = getStringOptional("actual_quantity"),
    confirmedBy = getString("confirmed_by"),
    givenByUserId = getStringOptional("given_by_user_id"),
    givenByOther = getLong("given_by_other"),
    givenByName = getStringOptional("given_by_name"),
    correctsEntryId = getStringOptional("corrects_entry_id"),
)

/** Rows to domain entries, dropping anything this app cannot read. */
internal fun List<DoseLogRow>.toEntries(): List<DoseLogEntry> = mapNotNull { it.toEntry() }

/*
 * The text forms below are the contract with the server. Each value is written
 * locally in the form Postgres would send it back in, so a row that has round-
 * tripped through sync is identical to one written on this phone, and a date or
 * slot comparison never depends on which phone produced the row.
 */

/** `date` → `YYYY-MM-DD`. */
internal fun formatDate(value: LocalDate): String = value.toString()

/** `date` → [LocalDate], or null when the text is not a date. */
internal fun parseDate(value: String?): LocalDate? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * `timestamp without time zone` → `YYYY-MM-DD HH:MM:SS`, the form Postgres uses
 * in its text output (a space, not `T`).
 *
 * Writing the same form locally is what keeps `scheduled_local` comparable: the
 * row a phone writes and the row sync writes back have the same bytes.
 */
internal fun formatScheduledLocal(value: LocalDateTime): String =
    value.format(SCHEDULED_LOCAL_FORMAT)

/**
 * The inverse of [formatScheduledLocal].
 *
 * Accepts the space form that comes from Postgres, an ISO `T` form (what a
 * hand-written test or a future PowerSync version may produce), fractional
 * seconds, and a trailing `Z`/offset (Postgres only appends one for
 * `timestamptz`, but tolerating it costs nothing). A value that is still not a
 * local date-time is null: a malformed row must not break a screen.
 */
internal fun parseScheduledLocal(value: String?): LocalDateTime? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val iso = text.replace(' ', 'T')

    runCatching { LocalDateTime.parse(iso) }.getOrNull()?.let { return it.toSlotPrecision() }

    val offset = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
    if (offset != null) return offset.toLocalDateTime().toSlotPrecision()

    val withoutZone = iso.removeSuffix("Z")
    return runCatching { LocalDateTime.parse(withoutZone) }.getOrNull()?.toSlotPrecision()
}

/**
 * A slot is minute-precise (the UI picks `HH:MM`), so a stored row's fractional
 * seconds are dropped.
 *
 * Postgres keeps microseconds on a `timestamp`, so a row inserted by another
 * client can carry `.123`; keeping it would make that row compare unequal to the
 * slot it answers, and a dose would look unconfirmed forever.
 */
private fun LocalDateTime.toSlotPrecision(): LocalDateTime =
    if (nano == 0) this else truncatedTo(ChronoUnit.SECONDS)

/** `HH:MM` for a regimen time (seconds are validated to be zero). */
internal fun formatTime(value: LocalTime): String =
    String.format(Locale.ROOT, "%02d:%02d", value.hour, value.minute)

/**
 * `regimens.times` → the JSON Postgres stores: a compact array of
 * `{"time":"HH:MM","quantity":number}`, ascending by time, quantities as JSON
 * numbers so the server's `times_are_valid` check accepts them.
 */
internal fun formatTimes(times: List<DoseTime>): String =
    times.sortedBy { it.time }.joinToString(prefix = "[", postfix = "]", separator = ",") { doseTime ->
        """{"time":"${formatTime(doseTime.time)}","quantity":${QuantityRules.format(doseTime.quantity)}}"""
    }

/**
 * `regimens.times` from SQLite, as a domain list, or null when the value is not
 * the expected array of `{time, quantity}` objects.
 *
 * Parsed as JSON rather than as text because Postgres re-serialises `jsonb` on
 * the way back (keys reordered, spaces added), so the exact bytes are not stable.
 */
internal fun parseTimes(json: String?): List<DoseTime>? {
    val text = json?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
    if (array.isEmpty()) return null

    val times = array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val time = (item["time"] as? JsonPrimitive)?.content
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: return@mapNotNull null
        val quantity = (item["quantity"] as? JsonPrimitive)?.content
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: return@mapNotNull null
        DoseTime(time = time, quantity = quantity)
    }

    // A partially readable array is not a schedule: refuse it whole.
    if (times.size != array.size) return null
    return times.sortedBy { it.time }
}

/** A `numeric` column as a [BigDecimal], or null when it is not a number. */
internal fun parseQuantity(value: String?): BigDecimal? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

/**
 * A quantity as the decimal text stored locally (`0.5`, `1`, `0.125`).
 *
 * Plain decimal, never scientific notation or a binary float: the value is
 * uploaded as a JSON number and Postgres stores it as `numeric(7,3)`.
 */
internal fun formatQuantity(value: BigDecimal): String = QuantityRules.format(value)

private val SCHEDULED_LOCAL_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
