package io.github.lgpy.familycare.feature.medications.doses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.ui.formatQuantity
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.labelRes
import io.github.lgpy.familycare.feature.medications.ui.localizedDate
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One dose slot (docs/ux/screens.md §2): every medication due at this time, each
 * with taken/skipped, a reason when skipped, and who gave it.
 *
 * Saving with no exception is the same one-tap confirmation the Today card
 * offers; opening the slot is what makes the exceptions possible.
 */
@Composable
fun DoseSlotScreen(
    patientId: String,
    scheduledLocal: java.time.LocalDateTime,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<DoseSlotViewModel>(
        parameters = { parametersOf(patientId, scheduledLocal.toString()) },
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Column {
                Text(
                    text = stringResource(
                        R.string.meds_slot_title,
                        uiState.items.firstOrNull()?.let { formatTime(it.slot.localTime) }
                            ?: formatTime(scheduledLocal.toLocalTime()),
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                listOfNotNull(
                    uiState.patientName,
                    uiState.items.firstOrNull()?.slot?.localDate?.let { localizedDate(it) },
                ).takeIf { it.isNotEmpty() }?.let { parts ->
                    Text(
                        text = parts.joinToString(stringResource(R.string.meds_list_separator)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        when {
            !uiState.loaded -> CircularProgressIndicator()
            uiState.notFound -> Text(
                text = stringResource(R.string.meds_slot_not_found),
                style = MaterialTheme.typography.bodyLarge,
            )

            else -> {
                if (!uiState.canWrite) {
                    Text(
                        text = stringResource(R.string.patient_read_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                uiState.items.forEach { item ->
                    HorizontalDivider()
                    SlotMedicationRow(
                        item = item,
                        enabled = uiState.canWrite,
                        onOutcome = { outcome ->
                            viewModel.onOutcome(item.slot.regimenId.value, outcome)
                        },
                        onSkipReason = { reason ->
                            viewModel.onSkipReason(item.slot.regimenId.value, reason)
                        },
                    )
                }

                HorizontalDivider()
                OutlinedTextField(
                    value = uiState.note,
                    onValueChange = viewModel::onNoteChanged,
                    label = { Text(text = stringResource(R.string.meds_note_label)) },
                    supportingText = {
                        Text(text = stringResource(R.string.meds_note_helper))
                    },
                    enabled = uiState.canWrite,
                    modifier = Modifier.fillMaxWidth(),
                )

                GivenBySection(
                    uiState = uiState,
                    onMe = viewModel::onGivenByMe,
                    onMember = viewModel::onGivenByMember,
                    onSomeoneElse = viewModel::onGivenBySomeoneElse,
                    onSomeoneName = viewModel::onSomeoneElseName,
                )

                uiState.error?.let { error ->
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (uiState.canWrite) {
                    Button(
                        onClick = viewModel::save,
                        enabled = !uiState.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.saving) R.string.meds_step_busy else R.string.meds_save,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotMedicationRow(
    item: SlotMedicationUi,
    enabled: Boolean,
    onOutcome: (DoseOutcome) -> Unit,
    onSkipReason: (SkipReason) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = item.name + stringResource(R.string.meds_list_separator) +
                formatQuantity(item.slot.quantity),
            style = MaterialTheme.typography.titleSmall,
        )
        item.existing?.let { entry ->
            Text(
                text = stringResource(
                    R.string.meds_existing_entry,
                    stringResource(entry.outcome.messageRes()),
                    formatTime(entry.actualAt.atZone(java.time.ZoneId.systemDefault()).toLocalTime()),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableOption(
                selected = item.outcome == DoseOutcome.TAKEN,
                label = stringResource(R.string.meds_taken),
                enabled = enabled,
                onClick = { onOutcome(DoseOutcome.TAKEN) },
            )
            SelectableOption(
                selected = item.outcome == DoseOutcome.SKIPPED,
                label = stringResource(R.string.meds_skipped),
                enabled = enabled,
                onClick = { onOutcome(DoseOutcome.SKIPPED) },
            )
        }
        if (item.outcome == DoseOutcome.SKIPPED) {
            Text(
                text = stringResource(R.string.meds_skip_reason_label),
                style = MaterialTheme.typography.bodySmall,
            )
            SkipReason.entries.forEach { reason ->
                SelectableOption(
                    selected = item.skipReason == reason,
                    label = stringResource(reason.labelRes()),
                    enabled = enabled,
                    onClick = { onSkipReason(reason) },
                )
            }
        }
    }
}

@Composable
private fun GivenBySection(
    uiState: DoseSlotUiState,
    onMe: () -> Unit,
    onMember: (String) -> Unit,
    onSomeoneElse: () -> Unit,
    onSomeoneName: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.meds_given_by_label),
        style = MaterialTheme.typography.titleSmall,
    )
    SelectableOption(
        selected = uiState.givenBy == GivenByChoice.Me,
        label = stringResource(R.string.meds_given_by_me),
        enabled = uiState.canWrite,
        onClick = onMe,
    )
    uiState.members.filterNot { it.isMe }.forEach { member ->
        SelectableOption(
            selected = uiState.givenBy == GivenByChoice.Member && uiState.memberId == member.userId,
            label = member.displayName
                ?: stringResource(R.string.family_circle_member_unnamed),
            enabled = uiState.canWrite,
            onClick = { onMember(member.userId) },
        )
    }
    SelectableOption(
        selected = uiState.givenBy == GivenByChoice.SomeoneElse,
        label = stringResource(R.string.meds_given_by_other_option),
        enabled = uiState.canWrite,
        onClick = onSomeoneElse,
    )
    if (uiState.givenBy == GivenByChoice.SomeoneElse) {
        OutlinedTextField(
            value = uiState.someoneName,
            onValueChange = onSomeoneName,
            label = { Text(text = stringResource(R.string.meds_given_by_name_label)) },
            enabled = uiState.canWrite,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A radio row without the radio's own semantics wrapper, for compact lists. */
@Composable
private fun SelectableOption(
    selected: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = 8.dp)
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}

/** "taken" / "skipped", for the "Saved: …" line of an existing entry. */
@Composable
private fun DoseOutcome.messageRes(): Int = when (this) {
    DoseOutcome.TAKEN -> R.string.meds_status_taken
    DoseOutcome.SKIPPED -> R.string.meds_status_skipped
}
