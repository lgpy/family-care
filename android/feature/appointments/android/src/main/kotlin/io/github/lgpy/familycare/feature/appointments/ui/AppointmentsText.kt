package io.github.lgpy.familycare.feature.appointments.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.appointments.data.AppointmentEntry
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * How the appointments screens write a kind, a date, a time and an error.
 *
 * Everything user-facing comes from `core:ui`'s string resources (en and
 * pt-PT), so no screen hard-codes text.
 */

/** The localized label of an appointment kind. */
@StringRes
fun AppointmentKind.labelRes(): Int = when (this) {
    AppointmentKind.CONSULTATION -> R.string.appointments_kind_consultation
    AppointmentKind.BLOOD_TEST -> R.string.appointments_kind_blood_test
    AppointmentKind.EXAM -> R.string.appointments_kind_exam
    AppointmentKind.CHECKUP -> R.string.appointments_kind_checkup
    AppointmentKind.OTHER -> R.string.appointments_kind_other
}

/** The inline message of a rejected field, or null when the field has no rule. */
@StringRes
fun AppointmentField.errorRes(): Int = when (this) {
    AppointmentField.PATIENT -> R.string.appointments_error_patient
    AppointmentField.KIND -> R.string.appointments_error_invalid
    AppointmentField.TITLE -> R.string.appointments_error_title
    AppointmentField.STARTS_LOCAL -> R.string.appointments_error_invalid
    AppointmentField.TIME_ZONE -> R.string.appointments_error_time_zone
    AppointmentField.DURATION -> R.string.appointments_error_duration
    AppointmentField.PLACE -> R.string.appointments_error_place
    AppointmentField.DOCTOR -> R.string.appointments_error_doctor
    AppointmentField.PREPARATION -> R.string.appointments_error_preparation
    AppointmentField.NOTES -> R.string.appointments_error_notes
    AppointmentField.COMPANION -> R.string.appointments_error_companion
    AppointmentField.FOLLOW_UP -> R.string.appointments_error_follow_up
}

/** The screen-level message of a failed write. */
@StringRes
fun AppointmentsUiError.messageRes(): Int = when (this) {
    AppointmentsUiError.NotAllowed -> R.string.appointments_error_not_allowed
    AppointmentsUiError.NotFound -> R.string.appointments_not_found
    AppointmentsUiError.Invalid -> R.string.appointments_error_invalid
    AppointmentsUiError.Unknown -> R.string.appointments_error_unknown
}

/** `09:30`, the 24-hour form both languages use for an appointment. */
fun formatAppointmentTime(time: LocalTime): String =
    time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')

/** A date in the user's language (`20 Nov 2026` / `20 nov 2026`). */
@Composable
fun localizedAppointmentDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) {
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

/**
 * The header of a day group: "Today", "Tomorrow" or the weekday and date.
 * [today] is the phone's date, so a group in another zone still says "Today"
 * only when it really is today here.
 */
@Composable
fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.appointments_today)
    today.plusDays(1) -> stringResource(R.string.appointments_tomorrow)
    else -> {
        val locale = LocalConfiguration.current.locales[0]
        remember(date, locale) {
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        }
    }
}

/**
 * One appointment line: time, kind or title, patient (in the Agenda) and place.
 * A cancelled appointment is struck through and labelled.
 */
@Composable
fun AppointmentLine(
    entry: AppointmentEntry,
    showPatient: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appointment = entry.appointment
    val cancelled = appointment.isCancelled
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = false, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val kindLabel = stringResource(appointment.kind.labelRes())
        val headline = formatAppointmentTime(appointment.localTime) +
            " · " +
            (appointment.title ?: kindLabel)
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (cancelled) TextDecoration.LineThrough else null,
        )
        if (showPatient && entry.patientName.isNotBlank()) {
            Text(text = entry.patientName, style = MaterialTheme.typography.bodyMedium)
        }
        appointment.place?.let { place ->
            Text(text = place, style = MaterialTheme.typography.bodyMedium)
        }
        if (cancelled) {
            Text(
                text = stringResource(R.string.appointments_cancelled),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** A field's inline error line, or nothing when it is not in [errors]. */
@Composable
fun FieldError(visible: Boolean, message: Int) {
    if (visible) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
