package io.github.lgpy.familycare.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.data.activity.ActivityEvent
import io.github.lgpy.familycare.core.data.activity.ActivityRepository
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.canManage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The owner-only activity feed (D30).
 *
 * [events] are newest first and stay in server order; the screen groups them by
 * day. [endReached] means the last page was short, so "Load more" disappears.
 * [error] is the last failed read (or [CoreError.NotAllowed] when the signed-in
 * role is not an owner); a refresh keeps the rows already read.
 */
data class ActivityUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val events: List<ActivityEvent> = emptyList(),
    val endReached: Boolean = false,
    val error: CoreError? = null,
) {
    /** Whether the first page has been read (successfully or not). */
    val loaded: Boolean get() = !loading
}

/**
 * Reads `core.circle_activity` one page at a time (D30).
 *
 * The audit log is not synced, so every read is online. The role check mirrors
 * the server's owner-only rule and gives the screen a translated message
 * instead of an empty list on a role it should not have opened.
 */
class ActivityViewModel(
    private val activityRepository: ActivityRepository,
    private val careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivityUiState(loading = true))
    val uiState: StateFlow<ActivityUiState> = _uiState

    init {
        refresh()
    }

    /** Reads the first page again, keeping the rows visible while it runs. */
    fun refresh() {
        val hasRows = _uiState.value.events.isNotEmpty()
        _uiState.update {
            it.copy(loading = !hasRows, refreshing = hasRows, error = null, endReached = false)
        }
        viewModelScope.launch {
            val circleId = ownerCircleId()
            if (circleId == null) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = if (careCircleRepository.circle.first() == null) {
                            CoreError.NoCircle
                        } else {
                            CoreError.NotAllowed
                        },
                    )
                }
                return@launch
            }
            when (val result = activityRepository.activity(circleId)) {
                is CoreResult.Success -> _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        events = result.value,
                        endReached = result.value.size < ActivityRepository.PAGE_SIZE,
                        error = null,
                    )
                }

                is CoreResult.Failure -> _uiState.update {
                    it.copy(loading = false, refreshing = false, error = result.error)
                }
            }
        }
    }

    /**
     * Appends the next page, using the oldest row read so far as `p_before`.
     * Does nothing while a read is in flight, after a short page, or after a
     * failure (the error line offers [refresh] instead).
     */
    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.refreshing || state.endReached || state.error != null) return
        val before = state.events.lastOrNull()?.occurredAt ?: return
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val circleId = ownerCircleId()
            if (circleId == null) {
                _uiState.update { it.copy(loading = false, error = CoreError.NotAllowed) }
                return@launch
            }
            when (val result = activityRepository.activity(circleId, before = before)) {
                is CoreResult.Success -> _uiState.update {
                    it.copy(
                        loading = false,
                        events = it.events + result.value,
                        endReached = result.value.size < ActivityRepository.PAGE_SIZE,
                        error = null,
                    )
                }

                is CoreResult.Failure -> _uiState.update {
                    it.copy(loading = false, error = result.error)
                }
            }
        }
    }

    private suspend fun ownerCircleId(): String? {
        if (!careCircleRepository.myRole.first().canManage) return null
        return careCircleRepository.circle.first()?.id?.value
    }
}
