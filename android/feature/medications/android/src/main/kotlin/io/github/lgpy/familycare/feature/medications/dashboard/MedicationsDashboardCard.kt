package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import java.time.LocalTime
import org.koin.compose.viewmodel.koinViewModel

/**
 * The medications module's contribution to Today: "Doses today"
 * (docs/ux/screens.md §1, docs/build-plan.md M3).
 *
 * The card sits after the needs-attention items and before the coming week. It
 * opens the dose-slot screen itself through [onNavigate], so the shell imports
 * neither the screen nor the module's view models.
 */
class MedicationsDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<DosesTodayViewModel>()
        val uiState by viewModel.uiState.collectAsState()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.meds_doses_today_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (uiState.groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.meds_doses_today_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                uiState.groups.forEach { group ->
                    DoseGroupRow(
                        group = group,
                        canWrite = uiState.canWrite,
                        saving = uiState.saving,
                        onOpen = {
                            onNavigate(
                                MedicationsRoutes.doseSlot(
                                    patientId = group.patientId.value,
                                    scheduledLocal = group.scheduledLocal,
                                ),
                            )
                        },
                        onGive = { viewModel.give(group.patientId, group.scheduledLocal) },
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

@Composable
private fun DoseGroupRow(
    group: DoseGroupUi,
    canWrite: Boolean,
    saving: Boolean,
    onOpen: () -> Unit,
    onGive: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.meds_open_slot, formatTime(group.time)), onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTime(group.time) + " · " + group.patientName,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = groupStatusLine(group),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (canWrite && !group.allConfirmed) {
            TextButton(onClick = onGive, enabled = !saving) {
                Text(text = stringResource(R.string.meds_give))
            }
        }
    }
}

/** "3 doses · due", plus "by Mom at 08:04" once something is recorded. */
@Composable
private fun groupStatusLine(group: DoseGroupUi): String {
    val separator = stringResource(R.string.meds_list_separator)
    val count = pluralStringResource(R.plurals.meds_doses_count, group.doseCount, group.doseCount)
    val state = stringResource(group.state.labelRes())
    val given = givenByLine(group.givenBy, group.at)
    return listOfNotNull(count, state, given).joinToString(separator)
}

@Composable
private fun givenByLine(givenBy: GivenByUi?, at: LocalTime?): String? {
    if (at == null) return null
    val time = formatTime(at)
    return when {
        givenBy == null -> null
        givenBy.someoneElse ->
            givenBy.name?.let { stringResource(R.string.meds_given_by_name, it, time) }
                ?: stringResource(R.string.meds_given_by_someone, time)

        givenBy.isMe -> stringResource(R.string.meds_given_by_you, time)
        else ->
            givenBy.name?.let { stringResource(R.string.meds_given_by_name, it, time) }
                ?: stringResource(R.string.meds_given_by_someone, time)
    }
}

@Composable
private fun DoseGroupState.labelRes(): Int =
    when (this) {
        DoseGroupState.UPCOMING -> R.string.meds_status_upcoming
        DoseGroupState.DUE -> R.string.meds_status_due
        DoseGroupState.OVERDUE -> R.string.meds_status_overdue
        DoseGroupState.MISSED -> R.string.meds_status_missed
        DoseGroupState.TAKEN -> R.string.meds_status_taken
        DoseGroupState.SKIPPED -> R.string.meds_status_skipped
        DoseGroupState.PARTLY -> R.string.meds_status_partly
    }

/**
 * The safety check before a second confirmation: "Already given by Mom at 08:04.
 * Did you give another one?" (docs/ux/screens.md §2, walkthrough).
 */
@Composable
private fun AlreadyGivenDialog(
    group: DoseGroupUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = when {
        group.givenBy?.isMe == true -> stringResource(R.string.meds_you)
        else -> group.givenBy?.name ?: stringResource(R.string.meds_someone_else)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.meds_already_given_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.meds_already_given_body,
                    name,
                    group.at?.let(::formatTime).orEmpty(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.meds_give_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
