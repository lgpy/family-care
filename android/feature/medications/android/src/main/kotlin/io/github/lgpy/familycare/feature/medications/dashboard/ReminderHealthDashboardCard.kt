package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.ui.R
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
 * high urgency (dashboard order 10, right after needs-attention) and links to
 * More → Reminders, where the user can fix it.
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
                    text = stringResource(R.string.reminders_health_card_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.reminders_health_card_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = current.problems.map { problemLabel(it) }.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onNavigate(ReminderRoutes.SETUP) }) {
                    Text(text = stringResource(R.string.reminders_health_card_action))
                }
            }
        }
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
