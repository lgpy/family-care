package io.github.lgpy.familycare.feature.medications.missed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.DoseSlotState
import io.github.lgpy.familycare.core.ui.components.DoseSlotUi
import io.github.lgpy.familycare.core.ui.components.DoseTimeGroup
import io.github.lgpy.familycare.core.ui.components.TimeRail
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.dashboard.DoseGroupState
import io.github.lgpy.familycare.feature.medications.dashboard.givenByText
import io.github.lgpy.familycare.feature.medications.data.RECENT_DOSE_DAYS
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.localizedWeekdayDate
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import java.time.LocalDate
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Missed doses" (docs/ux/mockups/missed-doses.html): the days before today,
 * newest first, each with the dose groups nobody confirmed on a time rail.
 *
 * Each open card has "It was given" (opens the dose-slot screen, which records
 * it at the scheduled time) and "Not given" (records it skipped at once). A
 * read-only member sees the same cards without buttons.
 */
@Composable
fun MissedDosesScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<MissedDosesViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val today = LocalDate.now()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(R.string.meds_missed_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier.padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.meds_missed_subtitle, RECENT_DOSE_DAYS),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                !uiState.loaded -> CircularProgressIndicator()

                uiState.days.isEmpty() -> Text(
                    text = stringResource(R.string.meds_missed_empty, RECENT_DOSE_DAYS),
                    style = MaterialTheme.typography.bodyLarge,
                )

                else -> {
                    if (!uiState.canWrite) {
                        Text(
                            text = stringResource(R.string.patient_read_only),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    uiState.error?.let { error ->
                        Text(
                            text = stringResource(error.messageRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    uiState.days.forEach { day ->
                        Text(
                            text = dayLabel(day.date, today),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        TimeRail(
                            groups = day.timeGroups(
                                canWrite = uiState.canWrite && !uiState.saving,
                                viewModel = viewModel,
                                onNavigate = onNavigate,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** "Yesterday", else "Thursday, 19 November" (capitalised in every language). */
@Composable
private fun dayLabel(date: LocalDate, today: LocalDate): String =
    if (date == today.minusDays(1)) {
        stringResource(R.string.meds_missed_yesterday)
    } else {
        localizedWeekdayDate(date).replaceFirstChar { it.titlecase() }
    }

@Composable
private fun MissedDay.timeGroups(
    canWrite: Boolean,
    viewModel: MissedDosesViewModel,
    onNavigate: (String) -> Unit,
): List<DoseTimeGroup> = groups
    .groupBy { it.dose.time }
    .toSortedMap()
    .map { (time, timeGroups) ->
        DoseTimeGroup(
            time = formatTime(time),
            slots = timeGroups
                .sortedBy { it.dose.patientName.lowercase() }
                .map { missed ->
                    val dose = missed.dose
                    val openSlot = {
                        onNavigate(
                            MedicationsRoutes.doseSlot(
                                patientId = dose.patientId.value,
                                scheduledLocal = dose.scheduledLocal,
                            ),
                        )
                    }
                    val actions = missed.open && canWrite
                    DoseSlotUi(
                        patientId = dose.patientId,
                        patientName = dose.patientName,
                        meta = pluralStringResource(
                            R.plurals.meds_doses_count,
                            dose.doseCount,
                            dose.doseCount,
                        ),
                        state = if (missed.open) DoseSlotState.UPCOMING else DoseSlotState.DONE,
                        pills = dose.pills,
                        givenText = when {
                            missed.open -> null
                            dose.state == DoseGroupState.SKIPPED ->
                                stringResource(R.string.meds_missed_marked_not_given)

                            else -> givenByText(dose)
                        },
                        actionLabel = if (actions) stringResource(R.string.meds_missed_given) else null,
                        onAction = if (actions) {
                            {
                                viewModel.onGiven(dose.patientId, dose.scheduledLocal)
                                openSlot()
                            }
                        } else {
                            null
                        },
                        secondaryActionLabel = if (actions) {
                            stringResource(R.string.meds_missed_not_given)
                        } else {
                            null
                        },
                        onSecondaryAction = if (actions) {
                            { viewModel.onNotGiven(dose.patientId, dose.scheduledLocal) }
                        } else {
                            null
                        },
                        onClick = openSlot,
                    )
                },
        )
    }
