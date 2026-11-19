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
import io.github.lgpy.familycare.core.ui.components.AttentionAction
import io.github.lgpy.familycare.core.ui.components.AttentionCard
import io.github.lgpy.familycare.core.ui.components.AttentionItem
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealth
import io.github.lgpy.familycare.feature.medications.reminders.ReminderProblem
import io.github.lgpy.familycare.feature.medications.reminders.ReminderRoutes
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import org.koin.compose.viewmodel.koinViewModel

/**
 * Today's single "Needs attention" card (dashboard order 0): every amber row of
 * the medicines module in one light card, as the approved mockup draws it
 * (`docs/ux/mockups/screens-v2.html` `const today`).
 *
 * The card merges what used to be three separate cards, in the mockup's order:
 *
 * 1. **Unconfirmed doses** ([NeedsAttentionViewModel]) — overdue and missed
 *    groups, most urgent first. The row's small button is the existing one-tap
 *    group confirmation ("Give" / "Give all 4"); tapping the row opens the
 *    dose-slot screen, where a dose can still be recorded or skipped.
 * 2. **Low stock** ([LowStockViewModel]) — up to five medications, fewest days
 *    left first, then "+N more". The row opens the Shopping tab.
 * 3. **Reminder health** ([ReminderHealthViewModel]) — only while the phone's
 *    reminder setup is broken; the detail names what to turn on and the row
 *    opens More → Reminders.
 *
 * With nothing to say the card draws nothing at all. Every write and every
 * route stays the module's own, so the shell imports neither the screens nor
 * the view models (D5).
 */
class NeedsAttentionDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val dosesViewModel = koinViewModel<NeedsAttentionViewModel>()
        val stockViewModel = koinViewModel<LowStockViewModel>()
        val reminderViewModel = koinViewModel<ReminderHealthViewModel>()

        val doses by dosesViewModel.uiState.collectAsState()
        val stock by stockViewModel.uiState.collectAsState()
        val health by reminderViewModel.state.collectAsState()

        val rows = attentionRows(doses = doses, stock = stock, health = health)

        if (rows.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AttentionCard(
                    items = rows.map { row ->
                        row.toItem(
                            canWrite = doses.canWrite,
                            onGive = { group ->
                                dosesViewModel.give(group.patientId, group.scheduledLocal)
                            },
                            onNavigate = onNavigate,
                        )
                    },
                )

                doses.error?.let { error ->
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        doses.warning?.let { warning ->
            AlreadyGivenDialog(
                group = warning,
                onDismiss = dosesViewModel::dismissWarning,
                onConfirm = dosesViewModel::giveAnyway,
            )
        }
    }

    companion object {

        /** Needs-attention items sort above every other card. */
        const val ORDER: Int = 0
    }
}

/**
 * One row of the merged card, before it is turned into text and colours.
 *
 * Keeping the merge as plain data (no composables, no resources) makes the
 * card's ordering and its "nothing to say" rule testable without a device.
 */
internal sealed interface AttentionRow {

    /** An overdue or missed dose group of [NeedsAttentionViewModel]. */
    data class DoseGroup(val group: DoseGroupUi) : AttentionRow

    /** One medication of [LowStockViewModel] about to run out. */
    data class LowStock(val line: LowStockLine) : AttentionRow

    /** The "+N more" line of the low-stock digest. */
    data class LowStockMore(val count: Int) : AttentionRow

    /** The phone's reminder setup, when the check found something wrong. */
    data class Reminders(val problems: List<ReminderProblem>) : AttentionRow
}

/**
 * The merged card's rows, in the mockup's order: doses first (most urgent),
 * then low stock with its "+N more", then the reminder health row last.
 *
 * A healthy reminder setup and an empty stock digest contribute nothing, so an
 * empty result means the card is hidden.
 */
internal fun attentionRows(
    doses: NeedsAttentionUiState,
    stock: LowStockUiState,
    health: ReminderHealth?,
): List<AttentionRow> = buildList {
    doses.groups.forEach { group -> add(AttentionRow.DoseGroup(group)) }
    stock.lines.forEach { line -> add(AttentionRow.LowStock(line)) }
    if (stock.more > 0) add(AttentionRow.LowStockMore(stock.more))
    if (health != null && !health.isHealthy) add(AttentionRow.Reminders(health.problems))
}

@Composable
private fun AttentionRow.toItem(
    canWrite: Boolean,
    onGive: (DoseGroupUi) -> Unit,
    onNavigate: (String) -> Unit,
): AttentionItem = when (this) {
    is AttentionRow.DoseGroup -> AttentionItem(
        icon = FcIcons.MedicationFilled,
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
        action = if (canWrite && !group.allConfirmed) {
            AttentionAction(
                label = pluralStringResource(
                    R.plurals.meds_give_all,
                    group.doseCount,
                    group.doseCount,
                ),
                onClick = { onGive(group) },
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

    is AttentionRow.LowStock -> AttentionItem(
        icon = FcIcons.Inventory2,
        title = stringResource(
            R.string.meds_attention_low_stock_title,
            medicationLabel(line),
        ),
        supportingText = lowStockSupporting(line),
        onClick = { onNavigate(MedicationsRoutes.SHOPPING) },
    )

    is AttentionRow.LowStockMore -> AttentionItem(
        icon = FcIcons.Inventory2,
        title = stringResource(R.string.meds_low_stock_more, count),
        onClick = { onNavigate(MedicationsRoutes.SHOPPING) },
    )

    is AttentionRow.Reminders -> AttentionItem(
        icon = FcIcons.NotificationsOff,
        title = stringResource(R.string.reminders_health_card_title),
        supportingText = stringResource(
            R.string.reminders_health_card_body,
            problemList(problems),
        ),
        onClick = { onNavigate(ReminderRoutes.SETUP) },
    )
}

/** "Metformin 850 mg": the name and its strength, never truncated. */
@Composable
private fun medicationLabel(line: LowStockLine): String =
    listOfNotNull(line.medicationName, line.strength).joinToString(" ")

/** "António, 5 days left · needs prescription", or without the prescription note. */
@Composable
private fun lowStockSupporting(line: LowStockLine): String {
    val resource = if (line.needsPrescription) {
        R.plurals.meds_attention_low_stock_prescription
    } else {
        R.plurals.meds_attention_low_stock
    }
    return pluralStringResource(resource, line.daysRemaining, line.patientName, line.daysRemaining)
}

/**
 * The problems as one phrase: "notifications and battery exemption",
 * "notifications, exact alarms and scheduled alarms". Built from the same
 * [ReminderHealth.problems] the checks found, so the sentence never lists
 * something that is fine.
 */
@Composable
private fun problemList(problems: List<ReminderProblem>): String {
    val labels = problems.map { problemLabel(it) }
    if (labels.size <= 1) return labels.joinToString("")
    val separator = stringResource(R.string.meds_list_comma)
    return labels.dropLast(1).joinToString(separator) +
        stringResource(R.string.reminders_health_and) +
        labels.last()
}

@Composable
private fun problemLabel(problem: ReminderProblem): String = stringResource(
    when (problem) {
        ReminderProblem.NOTIFICATIONS,
        ReminderProblem.CHANNEL,
        -> R.string.reminders_health_notifications

        ReminderProblem.EXACT_ALARMS -> R.string.reminders_health_exact_alarms
        ReminderProblem.BATTERY_OPTIMISATION -> R.string.reminders_health_battery
        ReminderProblem.ALARMS -> R.string.reminders_health_alarms
    },
)
