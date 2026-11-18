package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import org.koin.compose.viewmodel.koinViewModel

/**
 * Today's "Needs attention" card: overdue and missed dose groups, most urgent
 * first (dashboard order 0, above "Doses today").
 *
 * A missed dose is not lost: it can still be recorded, and doing so resolves
 * the slot (docs/domain/medications.md "Dose log"). The same "Give ✓" and
 * double-confirmation dialog as the doses-today card are reused.
 */
class NeedsAttentionDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<NeedsAttentionViewModel>()
        val uiState by viewModel.uiState.collectAsState()

        if (uiState.groups.isEmpty()) return

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.reminders_needs_attention_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                uiState.groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onNavigate(
                                    MedicationsRoutes.doseSlot(
                                        patientId = group.patientId.value,
                                        scheduledLocal = group.scheduledLocal,
                                    ),
                                )
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = needsAttentionLine(group),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (uiState.canWrite && !group.allConfirmed) {
                            TextButton(
                                onClick = {
                                    viewModel.give(group.patientId, group.scheduledLocal)
                                },
                                enabled = !uiState.saving,
                            ) {
                                Text(text = stringResource(R.string.meds_give))
                            }
                        }
                    }
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

    /** "Grandma · 08:00 · 4 medications · not confirmed". */
    @Composable
    private fun needsAttentionLine(group: DoseGroupUi): String {
        val count = pluralStringResource(
            R.plurals.reminder_notification_medications,
            group.doseCount,
            group.doseCount,
        )
        return stringResource(
            R.string.reminders_needs_attention_line,
            group.patientName,
            formatTime(group.time),
            count,
        )
    }

    companion object {
        /** Needs-attention items sort above every other card. */
        const val ORDER: Int = 0
    }
}
