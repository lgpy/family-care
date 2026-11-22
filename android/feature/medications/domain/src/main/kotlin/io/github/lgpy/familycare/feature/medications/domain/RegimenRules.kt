package io.github.lgpy.familycare.feature.medications.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The regimen rules from docs/domain/medications.md, including versioning
 * (D9): a dose change never edits a regimen, it ends the current version and
 * starts a new one.
 *
 * Everything here is pure and clock-free: callers pass the date a change takes
 * effect, so the same rules run in tests, in the app and (mirrored in
 * TypeScript) on the server.
 *
 * ### Versioning contract
 * - [changeRegimen] requires `effectiveFrom > current.startDate`; a change on
 *   the day the regimen started would leave a zero-day version and make history
 *   ambiguous. The UI asks for the next day instead.
 * - When the current version has an end date, a change may take effect at most
 *   on that last day. Later than that would leave the new version no days (or,
 *   further out, days with no regimen); that is a new regimen, not a change.
 * - A dose change of an every-N-days regimen that keeps N keeps its phase: the
 *   new version starts on the first day of the old phase on or after
 *   `effectiveFrom`, so a change never brings a dose forward.
 * - The new version inherits the current version's `endDate`, so a treatment
 *   that was due to stop on 30 April still stops on 30 April after a dose change.
 * - [stop] may only move the end date earlier, never later, and never before the
 *   start date.
 */
object RegimenRules {

    /** At most this many fixed times per day; more is a data-entry mistake. */
    const val MAX_TIMES_PER_DAY: Int = 12

    /** Mirrors `meds.regimens_interval_range`. */
    const val MIN_INTERVAL_DAYS: Int = 2
    const val MAX_INTERVAL_DAYS: Int = 365

    /**
     * The intrinsic rules: the schedule fields agree with the schedule type,
     * the date bounds are ordered, and the times are storable.
     *
     * Ordering of [Regimen.times] is **not** an error: the model sorts them
     * ([Regimen.sortedTimes], [Regimen.normalized]) so a caller may pass them in
     * any order. Duplicate times are an error because the quantity of the
     * second would be ambiguous.
     */
    fun validate(regimen: Regimen): MedicationValidation {
        val errors = mutableSetOf<MedicationField>()

        when (regimen.scheduleType) {
            ScheduleType.DAILY -> {
                if (regimen.weekdays != null) errors += MedicationField.REGIMEN_WEEKDAYS
                if (regimen.intervalDays != null) errors += MedicationField.REGIMEN_INTERVAL_DAYS
            }

            ScheduleType.WEEKDAYS -> {
                if (!WeekdayMask.isValid(regimen.weekdays)) {
                    errors += MedicationField.REGIMEN_WEEKDAYS
                }
                if (regimen.intervalDays != null) errors += MedicationField.REGIMEN_INTERVAL_DAYS
            }

            ScheduleType.EVERY_N_DAYS -> {
                val interval = regimen.intervalDays
                if (interval == null || interval !in MIN_INTERVAL_DAYS..MAX_INTERVAL_DAYS) {
                    errors += MedicationField.REGIMEN_INTERVAL_DAYS
                }
                if (regimen.weekdays != null) errors += MedicationField.REGIMEN_WEEKDAYS
            }
        }

        val endDate = regimen.endDate
        if (endDate != null && endDate.isBefore(regimen.startDate)) {
            errors += MedicationField.REGIMEN_END_DATE
        }

        val times = regimen.times
        if (times.size !in 1..MAX_TIMES_PER_DAY) {
            errors += MedicationField.REGIMEN_TIMES
        }
        if (times.any { !it.isValid }) {
            errors += MedicationField.REGIMEN_TIMES
        }
        if (times.map { it.time }.toSet().size != times.size) {
            errors += MedicationField.REGIMEN_TIMES
        }

        return MedicationValidation(errors)
    }

    /**
     * A new version of [current] from [effectiveFrom], keeping the medication,
     * the treatment's end date and (unless overridden) the zone and schedule.
     *
     * [current] is ended on the day before [effectiveFrom]; the returned
     * [RegimenChange.Changed] pair therefore never overlaps and never leaves a
     * gap. When the dates allow the change, both rows are validated and every
     * field error is returned together so the form can mark all of them at once;
     * a rejected date returns only the date error.
     *
     * The caller supplies [newId] (generated on the phone, D3) and must not
     * reuse the current version's id.
     *
     * @param times The new times (quantity changes live here). Defaults to the
     *   current ones.
     */
    fun changeRegimen(
        current: Regimen,
        effectiveFrom: LocalDate,
        newId: RegimenId,
        times: List<DoseTime> = current.times,
        scheduleType: ScheduleType = current.scheduleType,
        weekdays: Int? = current.weekdays,
        intervalDays: Int? = current.intervalDays,
        timeZone: ZoneId = current.timeZone,
    ): RegimenChange {
        require(newId != current.id) { "A regimen change needs a new id" }

        val errors = mutableSetOf<MedicationField>()

        if (!effectiveFrom.isAfter(current.startDate)) {
            // A change on (or before) the first day would leave a zero-day version.
            errors += MedicationField.REGIMEN_START_DATE
        }
        val currentEnd = current.endDate
        if (currentEnd != null && effectiveFrom.isAfter(currentEnd)) {
            // The current version ends before the change: the new version would
            // have no days. Starting a new regimen is the right operation.
            errors += MedicationField.REGIMEN_START_DATE
        }

        if (errors.isNotEmpty()) {
            return RegimenChange.Rejected(errors)
        }

        // An every-N-days regimen is anchored on its start date, so a dose change
        // that keeps N must keep the phase: start on the first day of the old
        // phase on or after effectiveFrom, not on effectiveFrom itself.
        val startDate = phaseAlignedStart(current, effectiveFrom, scheduleType, intervalDays)
        if (currentEnd != null && startDate.isAfter(currentEnd)) {
            // The next dose of the old phase is after the end date: the new
            // version would have no days.
            return RegimenChange.Rejected(setOf(MedicationField.REGIMEN_START_DATE))
        }

        val ended = current.copy(endDate = effectiveFrom.minusDays(1)).normalized()
        val started = Regimen(
            id = newId,
            medicationId = current.medicationId,
            scheduleType = scheduleType,
            startDate = startDate,
            timeZone = timeZone,
            times = times,
            endDate = currentEnd,
            weekdays = weekdays,
            intervalDays = intervalDays,
        ).normalized()

        errors += validate(ended).errors
        errors += validate(started).errors
        if (errors.isNotEmpty()) {
            return RegimenChange.Rejected(errors)
        }
        return RegimenChange.Changed(ended = ended, started = started)
    }

    /**
     * The start date of the version that replaces [current] from [effectiveFrom].
     *
     * [effectiveFrom] itself, except when both versions are every-N-days with
     * the same N: then the first date on [current]'s phase that is on or after
     * [effectiveFrom] (so a dose that was not due is not made due by the change).
     * [effectiveFrom] is after [current]'s start date here, so the offset is
     * positive.
     */
    private fun phaseAlignedStart(
        current: Regimen,
        effectiveFrom: LocalDate,
        scheduleType: ScheduleType,
        intervalDays: Int?,
    ): LocalDate {
        val interval = current.intervalDays
        if (current.scheduleType != ScheduleType.EVERY_N_DAYS ||
            scheduleType != ScheduleType.EVERY_N_DAYS ||
            interval == null ||
            interval != intervalDays
        ) {
            return effectiveFrom
        }
        val offset = ChronoUnit.DAYS.between(current.startDate, effectiveFrom)
        val remainder = offset % interval
        return if (remainder == 0L) effectiveFrom else effectiveFrom.plusDays(interval - remainder)
    }

    /**
     * Ends [regimen] on [lastDay] (inclusive), the treatment being stopped or
     * finished.
     *
     * The new end date may not be before [Regimen.startDate] — a regimen must
     * keep at least one day — and may not be after an existing end date,
     * because stopping never extends a treatment. Use [changeRegimen] to change
     * the schedule.
     */
    fun stop(regimen: Regimen, lastDay: LocalDate): RegimenStop {
        val errors = mutableSetOf<MedicationField>()

        if (lastDay.isBefore(regimen.startDate)) {
            errors += MedicationField.REGIMEN_END_DATE
        }
        val currentEnd = regimen.endDate
        if (currentEnd != null && lastDay.isAfter(currentEnd)) {
            errors += MedicationField.REGIMEN_END_DATE
        }
        if (errors.isNotEmpty()) {
            return RegimenStop.Rejected(errors)
        }

        val stopped = regimen.copy(endDate = lastDay).normalized()
        errors += validate(stopped).errors
        return if (errors.isEmpty()) {
            RegimenStop.Stopped(stopped)
        } else {
            RegimenStop.Rejected(errors)
        }
    }

    /**
     * The instant a stop with [lastDay] takes effect: the start of the day
     * after [lastDay] in [timeZone] (the regimen's zone), or [now] when that
     * instant is already past. This is what `meds.medications.stopped_at`
     * stores; a medication counts as stopped once `stopped_at <= now`.
     */
    fun stopEffectiveAt(lastDay: LocalDate, timeZone: ZoneId, now: Instant): Instant {
        val dayAfter = lastDay.plusDays(1).atStartOfDay(timeZone).toInstant()
        return if (dayAfter.isBefore(now)) now else dayAfter
    }

    /**
     * Every pair of versions of the same medication whose date ranges intersect.
     *
     * Adjacent versions (one ends on 9 March, the next starts on 10 March) do
     * not overlap. An open-ended version extends to infinity, so it overlaps
     * every later version of the same medication. Regimens of different
     * medications are never compared.
     */
    fun findOverlaps(regimens: List<Regimen>): List<RegimenOverlap> {
        val overlaps = mutableListOf<RegimenOverlap>()
        val byMedication = regimens.groupBy { it.medicationId }
        byMedication.forEach { (_, versions) ->
            val ordered = versions.sortedWith(
                compareBy({ it.startDate }, { it.endDate ?: LocalDate.MAX }, { it.id.value }),
            )
            for (i in ordered.indices) {
                for (j in i + 1 until ordered.size) {
                    val earlier = ordered[i]
                    val later = ordered[j]
                    // `ordered` is by start date, so only the later start needs
                    // comparing against the earlier end.
                    val earlierEnd = earlier.endDate ?: LocalDate.MAX
                    if (!later.startDate.isAfter(earlierEnd)) {
                        overlaps += RegimenOverlap(earlier = earlier, later = later)
                    }
                }
            }
        }
        return overlaps.sortedWith(
            compareBy(
                { it.earlier.medicationId.value },
                { it.earlier.startDate },
                { it.later.startDate },
                { it.earlier.id.value },
                { it.later.id.value },
            ),
        )
    }

    /** Whether any two versions of the same medication overlap. */
    fun hasOverlaps(regimens: List<Regimen>): Boolean = findOverlaps(regimens).isNotEmpty()

    /**
     * The overlap rule as a validation, for a screen that saves a whole
     * medication's version list at once.
     */
    fun validateNoOverlap(regimens: List<Regimen>): MedicationValidation =
        if (hasOverlaps(regimens)) {
            MedicationValidation(setOf(MedicationField.REGIMEN_OVERLAP))
        } else {
            MedicationValidation()
        }
}

/** The outcome of [RegimenRules.changeRegimen]. */
sealed interface RegimenChange {

    /** Both rows to persist: the ended current version and the new one. */
    data class Changed(val ended: Regimen, val started: Regimen) : RegimenChange

    /** Nothing may be saved; [errors] says which fields to mark. */
    data class Rejected(val errors: Set<MedicationField>) : RegimenChange
}

/** The outcome of [RegimenRules.stop]. */
sealed interface RegimenStop {

    /** The regimen with its end date set to the last day. */
    data class Stopped(val regimen: Regimen) : RegimenStop

    /** Nothing may be saved; [errors] says which fields to mark. */
    data class Rejected(val errors: Set<MedicationField>) : RegimenStop
}

/**
 * Two versions of one medication whose inclusive date ranges intersect,
 * `earlier` being the one that starts first.
 */
data class RegimenOverlap(val earlier: Regimen, val later: Regimen)
