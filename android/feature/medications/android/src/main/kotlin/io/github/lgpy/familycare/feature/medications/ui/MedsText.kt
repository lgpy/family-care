package io.github.lgpy.familycare.feature.medications.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.QuantityRules
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.domain.WeekdayMask
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * How the medications screens write quantities, times and schedules.
 *
 * Everything user-facing comes from `core:ui`'s string resources (en and
 * pt-PT), so no screen hard-codes text. Only the fraction glyphs are literals:
 * `½` is the same character in both languages, and a quantity is a number, not
 * a sentence.
 */

/** `1`, `0.5` → `½`, `0.25` → `¼`, `1.75` → `1¾`; anything else stays a plain decimal. */
fun formatQuantity(quantity: BigDecimal): String {
    val normalized = QuantityRules.normalize(quantity)
    if (normalized.signum() < 0) return QuantityRules.format(normalized)
    val whole = normalized.toBigInteger()
    val glyph = when (normalized.subtract(normalized.setScale(0, java.math.RoundingMode.DOWN))) {
        BigDecimal("0.25") -> "¼"
        BigDecimal("0.5") -> "½"
        BigDecimal("0.75") -> "¾"
        else -> null
    }
    return when {
        glyph == null -> QuantityRules.format(normalized)
        whole.signum() == 0 -> glyph
        else -> "$whole$glyph"
    }
}

/** `08:04`, the 24-hour form both languages use for a dose. */fun formatTime(time: LocalTime): String =
    buildString {
        append(time.hour.toString().padStart(2, '0'))
        append(':')
        append(time.minute.toString().padStart(2, '0'))
    }

/** A date in the user's language (`16 nov 2026` / `16 Nov 2026`). */
@Composable
fun localizedDate(date: LocalDate): String {
    // locales is never empty in a Configuration; reading it here keeps recomposition on locale change.
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) {
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

/**
 * "3 tablets left" / "3 comprimidos em stock".
 *
 * The stock unit is a stored token, not screen text ([defaultStockUnit] in the
 * form), so the label is the one place that can say it in the user's language
 * and in the right number: the units the form fills in are pluralised through
 * their own resources, and a unit the caregiver typed is shown exactly as
 * entered. The count is the whole part of the stock, which is what picks the
 * plural form; the number itself keeps its fraction ([formatQuantity]).
 */
@Composable
fun stockLeftText(quantity: BigDecimal, stockUnit: String): String {
    val count = quantity.max(BigDecimal.ZERO).toInt()
    return pluralStringResource(
        R.plurals.meds_stock_left,
        count,
        formatQuantity(quantity),
        stockUnitLabel(stockUnit, count),
    )
}

/** The plural form of a default stock unit, or the typed token unchanged. */
@Composable
private fun stockUnitLabel(unit: String, count: Int): String {
    val words = when (unit.trim().lowercase()) {
        "tablet" -> R.plurals.meds_unit_tablet
        "capsule" -> R.plurals.meds_unit_capsule
        "drop" -> R.plurals.meds_unit_drop
        "dose" -> R.plurals.meds_unit_dose
        "patch" -> R.plurals.meds_unit_patch
        "unit" -> R.plurals.meds_unit_unit
        // "ml" and "g" are symbols: the same in both numbers and languages.
        else -> return unit
    }
    return pluralStringResource(words, count)
}

/** "1 at 08:00 · ½ at 21:00", the times and amounts of one version. */
@Composable
fun regimenTimesSummary(regimen: Regimen): String {
    val separator = stringResource(R.string.meds_list_separator)
    val parts = mutableListOf<String>()
    for (doseTime in regimen.sortedTimes()) {
        parts += stringResource(
            R.string.meds_summary_item,
            formatQuantity(doseTime.quantity),
            formatTime(doseTime.time),
        )
    }
    return parts.joinToString(separator)
}

/** "1 at 08:00 · ½ at 21:00, daily" — the one-line regimen summary. */
@Composable
fun regimenSummary(regimen: Regimen): String {
    val times = regimenTimesSummary(regimen)
    return when (regimen.scheduleType) {
        ScheduleType.DAILY -> stringResource(R.string.meds_schedule_daily_summary, times)
        ScheduleType.WEEKDAYS -> stringResource(
            R.string.meds_schedule_weekdays_summary,
            times,
            weekdaysLabel(regimen.weekdays ?: WeekdayMask.ALL),
        )

        ScheduleType.EVERY_N_DAYS -> stringResource(
            R.string.meds_schedule_every_n_summary,
            times,
            regimen.intervalDays ?: 0,
        )
    }
}

/** "Mon, Wed, Fri" for a [WeekdayMask] value. */
@Composable
fun weekdaysLabel(mask: Int): String {
    val separator = stringResource(R.string.meds_list_comma)
    val parts = mutableListOf<String>()
    for (day in WeekdayMask.days(mask)) {
        parts += stringResource(day.labelRes())
    }
    return parts.joinToString(separator)
}

@StringRes
fun DayOfWeek.labelRes(): Int = when (this) {
    DayOfWeek.MONDAY -> R.string.meds_weekday_mon
    DayOfWeek.TUESDAY -> R.string.meds_weekday_tue
    DayOfWeek.WEDNESDAY -> R.string.meds_weekday_wed
    DayOfWeek.THURSDAY -> R.string.meds_weekday_thu
    DayOfWeek.FRIDAY -> R.string.meds_weekday_fri
    DayOfWeek.SATURDAY -> R.string.meds_weekday_sat
    DayOfWeek.SUNDAY -> R.string.meds_weekday_sun
}

@StringRes
fun MedicationForm.labelRes(): Int = when (this) {
    MedicationForm.TABLET -> R.string.meds_form_tablet
    MedicationForm.CAPSULE -> R.string.meds_form_capsule
    MedicationForm.DROPS -> R.string.meds_form_drops
    MedicationForm.LIQUID -> R.string.meds_form_liquid
    MedicationForm.INJECTION -> R.string.meds_form_injection
    MedicationForm.INHALER -> R.string.meds_form_inhaler
    MedicationForm.PATCH -> R.string.meds_form_patch
    MedicationForm.CREAM -> R.string.meds_form_cream
    MedicationForm.OTHER -> R.string.meds_form_other
}

@StringRes
fun ScheduleType.labelRes(): Int = when (this) {
    ScheduleType.DAILY -> R.string.meds_schedule_daily
    ScheduleType.WEEKDAYS -> R.string.meds_schedule_weekdays
    ScheduleType.EVERY_N_DAYS -> R.string.meds_schedule_every_n
}

@StringRes
fun SkipReason.labelRes(): Int = when (this) {
    SkipReason.REFUSED -> R.string.meds_skip_refused
    SkipReason.NOT_NEEDED -> R.string.meds_skip_not_needed
    SkipReason.UNAVAILABLE -> R.string.meds_skip_unavailable
    SkipReason.OTHER -> R.string.meds_skip_other
}

@StringRes
fun DoseState.labelRes(): Int = when (this) {
    DoseState.UPCOMING -> R.string.meds_status_upcoming
    DoseState.DUE -> R.string.meds_status_due
    DoseState.OVERDUE -> R.string.meds_status_overdue
    DoseState.MISSED -> R.string.meds_status_missed
    DoseState.TAKEN -> R.string.meds_status_taken
    DoseState.SKIPPED -> R.string.meds_status_skipped
}

/** The message a failed medications write shows. */
@StringRes
fun MedsUiError.messageRes(): Int = when (this) {
    MedsUiError.NotAllowed -> R.string.error_not_allowed
    MedsUiError.NotFound -> R.string.error_not_found
    MedsUiError.Invalid -> R.string.meds_error_invalid
    MedsUiError.Unknown -> R.string.error_unknown
}
