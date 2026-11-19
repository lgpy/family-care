package io.github.lgpy.familycare.feature.appointments.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.feature.appointments.data.AppointmentEntry
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * How the appointments screens write a kind, a day, a preparation, a date, a
 * time and an error.
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

/** The localized label of a "Before going" chip. */
@StringRes
fun PreparationTag.labelRes(): Int = when (this) {
    PreparationTag.FASTING -> R.string.appointments_prep_fasting
    PreparationTag.RESULTS -> R.string.appointments_prep_results
    PreparationTag.SNS_CARD -> R.string.appointments_prep_sns
    PreparationTag.MEDICINES -> R.string.appointments_prep_medicines
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
 * A day of the agenda as a small group label: the calendar icon and the day,
 * `onSurfaceVariant` above the day's rows (docs/ux/mockups/screens-v2.html
 * `const agenda`).
 *
 * It is deliberately lighter than [io.github.lgpy.familycare.core.ui.components.SectionHeader]:
 * a day groups rows, it is not a section of the screen.
 */
@Composable
fun AppointmentDayHeader(date: LocalDate, today: LocalDate, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = FcIcons.CalendarToday,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.titleSmall)),
        )
        Text(
            text = dayLabel(date, today),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One appointment row: a 40 dp kind tile, the bold title, the time with its
 * schedule icon and — where the list spans patients — the patient's avatar and
 * short name, the place, then the preparation tags.
 *
 * A cancelled appointment is struck through and labelled. Nothing truncates:
 * the title, name, place and tags wrap and the row grows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppointmentLine(
    entry: AppointmentEntry,
    showPatient: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appointment = entry.appointment
    val cancelled = appointment.isCancelled
    val patientName = entry.patientName.takeIf { showPatient && it.isNotBlank() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppointmentKindTile(kind = appointment.kind)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = appointment.title ?: stringResource(appointment.kind.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = FcIcons.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.bodySmall)),
                    )
                    Text(
                        text = formatAppointmentTime(appointment.localTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (patientName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PatientAvatar(
                            patientId = appointment.patientId,
                            name = patientName,
                            size = 18.dp,
                        )
                        Text(
                            text = patientName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            appointment.place?.takeIf { it.isNotBlank() }?.let { place ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = FcIcons.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.bodySmall)),
                    )
                    Text(
                        text = place,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PreparationTags(
                preparation = appointment.preparation,
                modifier = Modifier.padding(top = 2.dp),
            )

            if (cancelled) {
                Text(
                    text = stringResource(R.string.appointments_cancelled),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
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
