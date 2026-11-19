package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.AttentionAction
import io.github.lgpy.familycare.core.ui.components.AttentionCard
import io.github.lgpy.familycare.core.ui.components.AttentionItem
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import org.koin.compose.viewmodel.koinViewModel

/**
 * Today's "Needs attention" card: overdue and missed dose groups, most urgent
 * first (dashboard order 0, above "Doses today").
 *
 * The fixed amber [AttentionCard] is the only coloured block on Today. An
 * escalated, still-unconfirmed group gets the mockup's full-width action
 * ("Give all 4"), which is the same one-tap group confirmation as before; a
 * missed dose is not lost: the item itself opens the dose-slot screen, where it
 * can still be recorded or skipped.
 */
class NeedsAttentionDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<NeedsAttentionViewModel>()
        val uiState by viewModel.uiState.collectAsState()

        if (uiState.groups.isEmpty()) return

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AttentionCard(
                items = uiState.groups.map { group ->
                    AttentionItem(
                        icon = Icons.Filled.Warning,
                        title = stringResource(
                            R.string.meds_attention_group_title,
                            group.patientName,
                            formatTime(group.time),
                        ),
                        supportingText = pluralStringResource(
                            R.plurals.reminder_notification_medications,
                            group.doseCount,
                            group.doseCount,
                        ),
                        action = if (uiState.canWrite && !group.allConfirmed) {
                            AttentionAction(
                                label = pluralStringResource(
                                    R.plurals.meds_give_all,
                                    group.doseCount,
                                    group.doseCount,
                                ),
                                onClick = {
                                    viewModel.give(group.patientId, group.scheduledLocal)
                                },
                            )
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
        /** Needs-attention items sort above every other card. */
        const val ORDER: Int = 0
    }
}
