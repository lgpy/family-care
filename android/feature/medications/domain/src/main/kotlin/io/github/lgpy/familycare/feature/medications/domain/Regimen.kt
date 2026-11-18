package io.github.lgpy.familycare.feature.medications.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How often a regimen's times occur (docs/domain/medications.md "Regimen").
 *
 * The database stores the lowercase [serverValue]; an unknown value is not
 * mapped onto [DAILY], because that would silently change when doses are due:
 * [fromServer] returns null.
 */
enum class ScheduleType(val serverValue: String) {
    /** Every day at the regimen's times. */
    DAILY("daily"),

    /** Only on the days selected in the [WeekdayMask] at the regimen's times. */
    WEEKDAYS("weekdays"),

    /**
     * Every N days, anchored on the regimen's start date: a date qualifies when
     * `(date - startDate) days % N == 0`.
     */
    EVERY_N_DAYS("every_n_days"),
    ;

    companion object {
        /** The schedule with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): ScheduleType? =
            entries.firstOrNull { it.serverValue == value }
    }
}

/**
 * ISO weekday bitmask used by [ScheduleType.WEEKDAYS].
 *
 * Monday is bit 0 (value 1), Tuesday bit 1 (2), …, Sunday bit 6 (64), matching
 * `java.time.DayOfWeek.getValue()` minus one. Stored as an integer 1..127;
 * `0` is invalid (a regimen that never occurs) and [ALL] selects every day.
 */
object WeekdayMask {

    const val MONDAY: Int = 1 shl 0
    const val TUESDAY: Int = 1 shl 1
    const val WEDNESDAY: Int = 1 shl 2
    const val THURSDAY: Int = 1 shl 3
    const val FRIDAY: Int = 1 shl 4
    const val SATURDAY: Int = 1 shl 5
    const val SUNDAY: Int = 1 shl 6

    /** Monday to Sunday. */
    const val ALL: Int = 0b111_1111

    /** The smallest valid mask (Monday only). */
    const val MIN: Int = MONDAY

    /** The bit for [day]. */
    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    /** Whether [mask] includes [day]. */
    fun includes(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    /** The mask selecting exactly [days] (empty → 0, which validation rejects). */
    fun of(vararg days: DayOfWeek): Int = days.fold(0) { mask, day -> mask or bit(day) }

    /** The days [mask] selects, Monday first. */
    fun days(mask: Int): List<DayOfWeek> =
        DayOfWeek.entries.filter { includes(mask, it) }

    /** Whether [mask] is a storable weekday selection (1..127). */
    fun isValid(mask: Int?): Boolean = mask != null && mask in MIN..ALL
}

/**
 * One version of a medication's schedule. **Never edited in place** (D9).
 *
 * A dose change ends the current version on the day before the change and
 * inserts a new version from that day ([RegimenRules.changeRegimen]); history
 * stays accurate ("5 mg until 3 March, 10 mg since"). Only [endDate] may move,
 * and only earlier, when a treatment is stopped ([RegimenRules.stop]).
 *
 * Dates are local dates in [timeZone]: a regimen never crosses zones, and the
 * zone is part of the version so a dose computed for a past day keeps its
 * original meaning even after a time-zone change.
 *
 * @param weekdays The [WeekdayMask] (1..127) when [scheduleType] is
 *   [ScheduleType.WEEKDAYS]; null for the other schedules.
 * @param intervalDays N (2..365) when [scheduleType] is
 *   [ScheduleType.EVERY_N_DAYS]; null for the other schedules. The anchor is
 *   [startDate].
 * @param endDate Last day the regimen applies, inclusive, or null while open.
 * @param times Non-empty (≤ 12) fixed times with their quantities. Callers need
 *   not sort them; [sortedTimes] (and the slot calculation) orders them by
 *   local time. Duplicate times are rejected by [RegimenRules.validate].
 */
data class Regimen(
    val id: RegimenId,
    val medicationId: MedicationId,
    val scheduleType: ScheduleType,
    val startDate: LocalDate,
    val timeZone: ZoneId,
    val times: List<DoseTime>,
    val endDate: LocalDate? = null,
    val weekdays: Int? = null,
    val intervalDays: Int? = null,
) {

    /** Whether the regimen is still open (no end date yet). */
    val isOpenEnded: Boolean get() = endDate == null

    /** The times in ascending local-time order, as slots are emitted. */
    fun sortedTimes(): List<DoseTime> = times.sortedBy { it.time }

    /**
     * Whether this version applies on [date]: inside the start/end bounds and on
     * a day the schedule selects. Dates are in [timeZone].
     *
     * For [ScheduleType.EVERY_N_DAYS] the anchor is [startDate], so moving the
     * start date changes which days qualify; the offset is never negative here
     * because the bounds are checked first.
     */
    fun occursOn(date: LocalDate): Boolean {
        if (date.isBefore(startDate)) return false
        if (endDate != null && date.isAfter(endDate)) return false
        return when (scheduleType) {
            ScheduleType.DAILY -> true
            ScheduleType.WEEKDAYS ->
                weekdays?.let { WeekdayMask.includes(it, date.dayOfWeek) } ?: false
            ScheduleType.EVERY_N_DAYS -> intervalDays?.let { interval ->
                ChronoUnit.DAYS.between(startDate, date) % interval == 0L
            } ?: false
        }
    }

    /**
     * The same regimen with its times sorted and quantities normalized. Saving
     * a regimen goes through this, so a stored row is deterministic.
     */
    fun normalized(): Regimen = copy(times = times.map { it.normalized() }.sortedBy { it.time })

    /** Intrinsic validity, mirroring the `meds.regimens` check constraints. */
    fun validate(): MedicationValidation = RegimenRules.validate(this)
}
