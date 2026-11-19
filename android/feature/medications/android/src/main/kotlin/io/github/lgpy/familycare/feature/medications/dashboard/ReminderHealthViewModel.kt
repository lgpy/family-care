package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealth
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealthChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The reminder health check behind the reminder row of Today's attention card
 * (docs/build-plan.md §M4, D19).
 *
 * The row is absent while everything checks out; when something is off it names
 * what to turn on and opens More → Reminders, where the user can fix it. The
 * check runs once per view model and on [refresh].
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
