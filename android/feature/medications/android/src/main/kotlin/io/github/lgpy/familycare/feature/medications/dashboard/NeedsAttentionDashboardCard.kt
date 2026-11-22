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
import io.github.lgpy.familycare.core.ui.dashboard.DashboardActions
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.data.RECENT_DOSE_DAYS
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
 * 2. **Missed earlier** — one row while doses of the days before today are
 *    still missed ("4 missed doses · in the last 7 days · whose"); it opens
 *    the missed-doses list, where each can be marked given or not given.
 * 3. **Low stock** ([LowStockViewModel]) — one row naming the medication when
 *    a single one is low, otherwise one summary row ("21 medicines are running
 *    low", with whose they are). Either opens the Shopping tab, which holds
 *    the full list; the card stays a digest, not a second shopping list.
 * 4. **Reminder health** ([ReminderHealthViewModel]) — only while the phone's
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
    override fun Content(actions: DashboardActions) {
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
                            onNavigate = actions.onNavigate,
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

    /** Missed groups of earlier days: how many, and whose. */
    data class MissedEarlier(val count: Int, val patientNames: List<String>) : AttentionRow

    /** One medication of [LowStockViewModel] about to run out. */
    data class LowStock(val line: LowStockLine) : AttentionRow

    /** Several low medications as one row: how many, and whose (most urgent first). */
    data class LowStockSummary(val count: Int, val patientNames: List<String>) : AttentionRow

    /** The phone's reminder setup, when the check found something wrong. */
    data class Reminders(val problems: List<ReminderProblem>) : AttentionRow
}

/**
 * The merged card's rows, in the mockup's order: doses first (most urgent),
 * then the earlier days' missed doses, then low stock (one row, whatever the count), then the reminder health row.
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
    if (doses.missedEarlier.isNotEmpty()) {
        add(
            AttentionRow.MissedEarlier(
                count = doses.missedEarlier.size,
                patientNames = doses.missedEarlier.map { it.patientName }.distinct(),
            ),
        )
    }
    when (stock.lines.size) {
        0 -> Unit
        1 -> add(AttentionRow.LowStock(stock.lines.single()))
        else -> add(
            AttentionRow.LowStockSummary(
                count = stock.lines.size,
                patientNames = stock.lines.map { it.patientName }.distinct(),
            ),
        )
    }
    if (health != null && !health.isHealthy) add(AttentionRow.Reminders(health.problems))
}

/**
 * Whether a dose row offers its one-tap confirm button: only a member who may
 * write, and only while something is still open. A read-only viewer and a
 * fully confirmed group keep the chevron (the row still opens the dose slot).
 *
 * Separated from the composable so the rule is testable without a device; the
 * plural wording itself lives in `meds_give_all` and `meds_attention_medicines`.
 */
internal fun showsGiveAction(canWrite: Boolean, allConfirmed: Boolean): Boolean =
    canWrite && !allConfirmed

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
            R.plurals.meds_attention_medicines,
            group.doseCount,
            group.doseCount,
        ),
        action = if (showsGiveAction(canWrite, group.allConfirmed)) {
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

    is AttentionRow.MissedEarlier -> AttentionItem(
        icon = FcIcons.Schedule,
        title = pluralStringResource(R.plurals.meds_attention_missed_title, count, count),
        supportingText = stringResource(
            R.string.meds_attention_missed_body,
            RECENT_DOSE_DAYS,
            joinNames(patientNames),
        ),
        onClick = { onNavigate(MedicationsRoutes.MISSED_DOSES) },
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

    is AttentionRow.LowStockSummary -> AttentionItem(
        icon = FcIcons.Inventory2,
        title = pluralStringResource(R.plurals.meds_attention_low_stock_summary, count, count),
        supportingText = joinNames(patientNames),
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

/**
 * "António, 5 days left · needs prescription", or without the prescription
 * note; "António, out of stock" when nothing is left.
 */
@Composable
private fun lowStockSupporting(line: LowStockLine): String {
    if (line.daysRemaining <= 0) {
        val out = if (line.needsPrescription) {
            R.string.meds_attention_out_of_stock_prescription
        } else {
            R.string.meds_attention_out_of_stock
        }
        return stringResource(out, line.patientName)
    }
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
private fun problemList(problems: List<ReminderProblem>): String =
    joinNames(problems.map { problemLabel(it) })

/** "A", "A and B", "A, B and C" in the app's language. */
@Composable
private fun joinNames(labels: List<String>): String {
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
