package io.github.lgpy.familycare.feature.medications.ui

import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.QuantityRules
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.RegimenRules
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.domain.WeekdayMask
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One "time + amount" row of the schedule editor. */
data class DoseTimeEntry(
    val time: String = "08:00",
    val quantity: BigDecimal = BigDecimal.ONE,
) {
    /** The row as a dose time, or null when the time is not `HH:MM`. */
    fun toDoseTime(): DoseTime? = parseTime(time)?.let { DoseTime(it, quantity) }
}

/** `08:00`, `8:00` and `8` all mean 08:00; null when [value] is not a time. */
fun parseTime(value: String): LocalTime? {
    val parts = value.trim().split(':')
    if (parts.size !in 1..2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 0
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

/** A quantity one step up or down, kept inside the storable range. */
fun steppedQuantity(current: BigDecimal, steps: Int): BigDecimal {
    val step = BigDecimal("0.25")
    val next = current.add(step.multiply(BigDecimal(steps)))
    return when {
        next < step -> step
        next > QuantityRules.MAX -> QuantityRules.MAX
        else -> QuantityRules.normalize(next)
    }
}

/**
 * The state of the schedule editor shared by the add-medication flow's second
 * step and the "change dose or schedule" screen, plus its parsing and
 * validation.
 *
 * Times and dates stay text while they are being typed (a half-typed `1` is not
 * silently turned into 10:00); [parse] is what turns the whole editor into a
 * [Regimen], reporting the [MedicationField]s the domain rules rejected so a
 * form can mark every offending input at once.
 *
 * Defaults are today (start), no end date, one 08:00 dose of 1, every day.
 */
data class ScheduleFormState(
    val scheduleType: ScheduleType = ScheduleType.DAILY,
    val weekdays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val intervalDays: String = RegimenRules.MIN_INTERVAL_DAYS.toString(),
    val times: List<DoseTimeEntry> = listOf(DoseTimeEntry()),
    val startDate: String = LocalDate.now().toString(),
    val endDate: String = "",
) {

    /** The weekday mask for [ScheduleType.WEEKDAYS], or null when none is selected. */
    fun weekdayMask(): Int? =
        weekdays.takeIf { it.isNotEmpty() }?.let { WeekdayMask.of(*it.toTypedArray()) }

    /** The parsed interval for [ScheduleType.EVERY_N_DAYS], or null when invalid. */
    fun interval(): Int? = intervalDays.trim().toIntOrNull()

    /**
     * The regimen this editor describes, with the inputs the domain rejected.
     *
     * [regimen] is non-null whenever the text parsed, even when [fields] is not
     * empty: the caller saves only when [fields] is empty, and a form may still
     * show a preview.
     */
    fun parse(
        id: RegimenId,
        medicationId: MedicationId,
        timeZone: ZoneId = ZoneId.systemDefault(),
    ): ScheduleParse {
        val fields = mutableSetOf<MedicationField>()

        val start = runCatching { LocalDate.parse(startDate.trim()) }.getOrNull()
        if (start == null) fields += MedicationField.REGIMEN_START_DATE
        val end = endDate.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull() ?: run {
                fields += MedicationField.REGIMEN_END_DATE
                null
            }
        }

        val doseTimes = times.mapNotNull { it.toDoseTime() }
        if (doseTimes.isEmpty() || doseTimes.size != times.size) {
            fields += MedicationField.REGIMEN_TIMES
        }

        val mask = weekdayMask()
        if (scheduleType == ScheduleType.WEEKDAYS && mask == null) {
            fields += MedicationField.REGIMEN_WEEKDAYS
        }
        val interval = interval()
        if (scheduleType == ScheduleType.EVERY_N_DAYS &&
            (interval == null || interval !in RegimenRules.MIN_INTERVAL_DAYS..RegimenRules.MAX_INTERVAL_DAYS)
        ) {
            fields += MedicationField.REGIMEN_INTERVAL_DAYS
        }

        if (start == null) return ScheduleParse(regimen = null, fields = fields)

        val regimen = Regimen(
            id = id,
            medicationId = medicationId,
            scheduleType = scheduleType,
            startDate = start,
            timeZone = timeZone,
            times = doseTimes,
            endDate = end,
            weekdays = if (scheduleType == ScheduleType.WEEKDAYS) mask else null,
            intervalDays = if (scheduleType == ScheduleType.EVERY_N_DAYS) interval else null,
        )
        fields += RegimenRules.validate(regimen).errors
        return ScheduleParse(regimen = regimen.normalized(), fields = fields)
    }

    companion object {
        /** The editor for an existing version, keeping its schedule and times. */
        fun of(regimen: Regimen?): ScheduleFormState = regimen?.let {
            ScheduleFormState(
                scheduleType = it.scheduleType,
                weekdays = it.weekdays?.let(WeekdayMask::days)?.toSet()
                    ?: DayOfWeek.entries.toSet(),
                intervalDays = (it.intervalDays ?: RegimenRules.MIN_INTERVAL_DAYS).toString(),
                times = it.sortedTimes().map { doseTime ->
                    DoseTimeEntry(
                        time = formatTime(doseTime.time),
                        quantity = QuantityRules.normalize(doseTime.quantity),
                    )
                },
                startDate = it.startDate.toString(),
                endDate = it.endDate?.toString().orEmpty(),
            )
        } ?: ScheduleFormState()
    }
}

/** What [ScheduleFormState.parse] produced. */
data class ScheduleParse(
    val regimen: Regimen?,
    val fields: Set<MedicationField>,
) {
    val isValid: Boolean get() = regimen != null && fields.isEmpty()
}
