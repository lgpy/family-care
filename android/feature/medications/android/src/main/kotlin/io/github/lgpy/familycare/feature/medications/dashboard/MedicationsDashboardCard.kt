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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.DoseSlotState
import io.github.lgpy.familycare.core.ui.components.DoseSlotUi
import io.github.lgpy.familycare.core.ui.components.DoseTimeGroup
import io.github.lgpy.familycare.core.ui.components.TimeRail
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
 * time. A confirmed group is **Done** (neutral, dashed, with "Given by X at
 * HH:MM" when the log has it), the first unconfirmed group still at or after
 * now is **Next** (primary container, with the countdown under the time), and
 * everything later is **Upcoming**. Tapping a slot opens the existing dose-slot
 * screen; "Give" is the existing one-tap group confirmation, so the module's
 * behaviour is unchanged and only the presentation moved to the shared
 * components.
 */
class MedicationsDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<DosesTodayViewModel>()
        val uiState by viewModel.uiState.collectAsState()
        val now = LocalDateTime.now()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.meds_doses_today_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (uiState.groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.meds_doses_today_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TimeRail(groups = uiState.toTimeGroups(now, uiState.canWrite, viewModel, onNavigate))
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

/** One rail group per time, the next group first-class and the countdown on it. */
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

    val nextTime = byTime
        .filterValues { timeGroups -> timeGroups.any { !it.allConfirmed } }
        .keys
        .firstOrNull { time -> !time.isBefore(now.toLocalTime()) }

    return byTime.map { (time, timeGroups) ->
        DoseTimeGroup(
            time = formatTime(time),
            secondary = if (time == nextTime) countdown(now, timeGroups.first().scheduledLocal) else null,
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
                            time == nextTime -> DoseSlotState.NEXT
                            else -> DoseSlotState.UPCOMING
                        },
                        givenText = if (done && group.state != DoseGroupState.SKIPPED) {
                            givenByText(group)
                        } else {
                            null
                        },
                        actionLabel = if (!done && canWrite) stringResource(R.string.meds_give) else null,
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
 * "Given by Mom at 08:04" for a confirmed group, or null when the log has no
 * time to show. Never invents a giver.
 */
@Composable
private fun givenByText(group: DoseGroupUi): String? {
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
