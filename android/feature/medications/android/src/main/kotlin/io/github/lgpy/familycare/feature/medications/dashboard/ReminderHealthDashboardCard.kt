package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.AttentionAction
import io.github.lgpy.familycare.core.ui.components.AttentionCard
import io.github.lgpy.familycare.core.ui.components.AttentionItem
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealth
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealthChecker
import io.github.lgpy.familycare.feature.medications.reminders.ReminderProblem
import io.github.lgpy.familycare.feature.medications.reminders.ReminderRoutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * The reminder health check on Today (docs/build-plan.md §M4, D19).
 *
 * The card is absent while everything checks out; when something is off it is
 * an amber [AttentionCard] at dashboard order 10 (right after needs-attention)
 * with a full-width "Fix reminders" action that opens More → Reminders, where
 * the user can fix it.
 */
class ReminderHealthViewModel(
    private val checker: ReminderHealthChecker,
) : ViewModel() {

    private val _state = MutableStateFlow<ReminderHealth?>(null)
    val state: StateFlow<ReminderHealth?> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = runCatching { checker.check() }.getOrNull()
        }
    }
}

/** Today's warning card: only drawn while the phone's reminder setup is broken. */
class ReminderHealthDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<ReminderHealthViewModel>()
        val health by viewModel.state.collectAsState()
        val current = health ?: return
        if (current.isHealthy) return

        val body = stringResource(
            R.string.reminders_health_card_body,
            problemList(current.problems),
        )

        AttentionCard(
            items = listOf(
                AttentionItem(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.reminders_health_card_title),
                    supportingText = body,
                    action = AttentionAction(
                        label = stringResource(R.string.reminders_health_card_action),
                        onClick = { onNavigate(ReminderRoutes.SETUP) },
                    ),
                ),
            ),
        )
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

    companion object {
        /** After needs-attention (0), before doses (100); still high urgency. */
        const val ORDER: Int = 10
    }
}
