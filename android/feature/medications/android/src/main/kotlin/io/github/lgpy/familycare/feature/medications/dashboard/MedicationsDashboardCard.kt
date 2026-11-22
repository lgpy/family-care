package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.DoseSlotState
import io.github.lgpy.familycare.core.ui.components.DoseSlotUi
import io.github.lgpy.familycare.core.ui.components.DoseTimeGroup
import io.github.lgpy.familycare.core.ui.components.EmptyStateAction
import io.github.lgpy.familycare.core.ui.components.EmptyStateCard
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.SectionHeader
import io.github.lgpy.familycare.core.ui.components.TimeRail
import io.github.lgpy.familycare.core.ui.dashboard.DashboardActions
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import java.time.Duration
import java.time.LocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/**
 * The medications module's contribution to Today: "Doses today" as the design
 * system's [TimeRail] (docs/ux/screens.md §1, docs/ux/mockups/scaling.html).
 *
 * One [DoseTimeGroup] per wall-clock time, one [DoseSlotUi] per patient at that
 * time, each with a row of the medicines' pill pictures. A confirmed group is
 * **Done** (neutral, dashed, with "Given by X at HH:MM" when the log has it),
 * the first unconfirmed group still at or after now is **Next** (primary
 * container, with the countdown under the time), and everything later is
 * **Upcoming**. Tapping a slot opens the existing dose-slot screen; "Give" is
 * the existing one-tap group confirmation, so the module's behaviour is
 * unchanged and only the presentation moved to the shared components.
 *
 * The section header carries the medicines icon, like every other section title
 * on Today (docs/ux/design-system.md §3, §6).
 *
 * An empty card means one of two very different things. If the circle has no
 * patients yet, the card is the first-run guide: one call to action that opens
 * the shell's Add patient form, because nothing can be due before there is
 * someone to care for. Once a patient exists, an empty card is simply a quiet
 * day and keeps the passive "No doses are due today." line.
 */
class MedicationsDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(actions: DashboardActions) {
        val viewModel = koinViewModel<DosesTodayViewModel>()
        val uiState by viewModel.uiState.collectAsState()
        val now = LocalDateTime.now()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(
                title = stringResource(R.string.meds_doses_today_title),
                icon = FcIcons.Medication,
            )

            if (uiState.groups.isEmpty()) {
                when {
                    // Nothing is known yet: no empty message, so the card never
                    // flashes "no doses" before the patients have loaded.
                    !uiState.loaded -> Unit

                    !uiState.hasPatients && uiState.canWrite -> EmptyStateCard(
                        title = stringResource(R.string.today_empty_add_patient_title),
                        body = stringResource(R.string.today_empty_add_patient_body),
                        action = EmptyStateAction(
                            label = stringResource(R.string.patients_add),
                            onClick = actions.onAddPatient,
                        ),
                    )

                    else -> Text(
                        text = stringResource(R.string.meds_doses_today_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                TimeRail(
                    groups = uiState.toTimeGroups(
                        now = now,
                        canWrite = uiState.canWrite,
                        viewModel = viewModel,
                        onNavigate = actions.onNavigate,
                    ),
                )
            }

            uiState.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        uiState.warning?.let { warning ->
            AlreadyGivenDialog(
                group = warning,
                onDismiss = viewModel::dismissWarning,
                onConfirm = viewModel::giveAnyway,
            )
        }
    }

    companion object {
        /** After needs-attention (0), before the next seven days (200). */
        const val ORDER: Int = 100
    }
}

/** One rail group per time, the overdue group first-class and the countdown on it. */
@Composable
private fun DosesTodayUiState.toTimeGroups(
    now: LocalDateTime,
    canWrite: Boolean,
    viewModel: DosesTodayViewModel,
    onNavigate: (String) -> Unit,
): List<DoseTimeGroup> {
    val byTime = groups
        .groupBy { it.time }
        .toSortedMap()

    val nowTime = now.toLocalTime()
    val nextTime = byTime
        .filterValues { timeGroups -> timeGroups.any { !it.allConfirmed } }
        .keys
        .firstOrNull { time -> time.isAfter(nowTime) }

    return byTime.map { (time, timeGroups) ->
        val open = timeGroups.any { !it.allConfirmed }
        // An unrecorded slot at or past its time is overdue: it keeps the strong
        // button and gains the late line, so it is bolder than anything later.
        val overdue = open && !time.isAfter(nowTime)
        val late = if (overdue) lateSpan(now, timeGroups.first().scheduledLocal) else null
        DoseTimeGroup(
            time = formatTime(time),
            secondary = when {
                late != null -> lateText(late)
                time == nextTime -> countdown(now, timeGroups.first().scheduledLocal)
                else -> null
            },
            late = late != null,
            slots = timeGroups
                .sortedBy { it.patientName.lowercase() }
                .map { group ->
                    val done = group.allConfirmed
                    DoseSlotUi(
                        patientId = group.patientId,
                        patientName = group.patientName,
                        meta = pluralStringResource(
                            R.plurals.meds_doses_count,
                            group.doseCount,
                            group.doseCount,
                        ),
                        state = when {
                            done -> DoseSlotState.DONE
                            overdue -> DoseSlotState.NEXT
                            else -> DoseSlotState.UPCOMING
                        },
                        pills = group.pills,
                        givenText = if (done && group.state != DoseGroupState.SKIPPED) {
                            givenByText(group)
                        } else {
                            null
                        },
                        actionLabel = if (!done && canWrite) {
                            pluralStringResource(
                                R.plurals.meds_give_all,
                                group.doseCount,
                                group.doseCount,
                            )
                        } else {
                            null
                        },
                        onAction = if (!done && canWrite) {
                            { viewModel.give(group.patientId, group.scheduledLocal) }
                        } else {
                            null
                        },
                        onClick = {
                            onNavigate(
                                MedicationsRoutes.doseSlot(
                                    patientId = group.patientId.value,
                                    scheduledLocal = group.scheduledLocal,
                                ),
                            )
                        },
                    )
                },
        )
    }
}

/**
 * How far past its time an unrecorded slot is, or null while it is not late.
 * Pure, so the boundaries (just due, 40 minutes, whole hours) are unit-tested.
 */
internal sealed interface LateSpan {

    /** Under an hour: "40 min late". */
    data class Minutes(val minutes: Long) : LateSpan

    /** An hour or more: "2 h late", or "2 h 15 min late" when not whole hours. */
    data class Hours(val hours: Long, val minutes: Long) : LateSpan
}

/** The late span of [scheduled] at [now], or null at or before its time. */
internal fun lateSpan(now: LocalDateTime, scheduled: LocalDateTime): LateSpan? {
    val minutes = Duration.between(scheduled, now).toMinutes()
    return when {
        minutes < 1 -> null
        minutes < 60 -> LateSpan.Minutes(minutes)
        else -> LateSpan.Hours(minutes / 60, minutes % 60)
    }
}

/** "40 min late", "2 h late" or "2 h 15 min late". */
@Composable
private fun lateText(span: LateSpan): String = when (span) {
    is LateSpan.Minutes -> pluralStringResource(
        R.plurals.meds_late_minutes,
        span.minutes.toInt(),
        span.minutes,
    )

    is LateSpan.Hours -> if (span.minutes == 0L) {
        pluralStringResource(R.plurals.meds_late_hours, span.hours.toInt(), span.hours)
    } else {
        pluralStringResource(
            R.plurals.meds_late_hours_minutes,
            span.hours.toInt(),
            span.hours,
            span.minutes,
        )
    }
}

/**
 * "Given by Mom at 08:04" for a confirmed group, or null when the log has no
 * time to show. Never invents a giver.
 */
@Composable
internal fun givenByText(group: DoseGroupUi): String? {
    val at = group.at ?: return null
    val time = formatTime(at)
    val givenBy = group.givenBy
    return when {
        givenBy == null -> null
        givenBy.someoneElse ->
            givenBy.name?.let { stringResource(R.string.meds_slot_given_by_name, it, time) }
                ?: stringResource(R.string.meds_slot_given_by_someone, time)

        givenBy.isMe -> stringResource(R.string.meds_slot_given_by_you, time)
        else ->
            givenBy.name?.let { stringResource(R.string.meds_slot_given_by_name, it, time) }
                ?: stringResource(R.string.meds_slot_given_by_someone, time)
    }
}

/** "in 3 h 48 min", "in 12 min" or "now", only for the Next group. */
@Composable
private fun countdown(now: LocalDateTime, at: LocalDateTime): String {
    val minutes = Duration.between(now, at).toMinutes()
    return when {
        minutes <= 0 -> stringResource(R.string.meds_countdown_now)
        minutes < 60 -> stringResource(R.string.meds_countdown_minutes, minutes)
        else -> stringResource(
            R.string.meds_countdown_hours,
            minutes / 60,
            minutes % 60,
        )
    }
}
