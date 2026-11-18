package io.github.lgpy.familycare.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.sync.RejectedUpload
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The app shell's message about a change the server permanently refused.
 *
 * Only [RejectedUpload] is exposed, never [io.github.lgpy.familycare.core.domain.sync.SyncState.hasError]:
 * a transient failure (offline, retrying) is not something that was lost, and
 * showing it as a discarded change would be alarming and wrong.
 *
 * Dismissing is the acknowledgement: the message stays away even after later
 * uploads succeed, until the user dismisses it or a new refusal replaces it.
 */
class UploadRejectionViewModel(
    private val syncService: SyncService,
) : ViewModel() {

    val rejection: StateFlow<RejectedUpload?> = syncService.state
        .map { it.rejectedUpload }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = syncService.state.value.rejectedUpload,
        )

    /** The user has read the message; it will not come back. */
    fun dismiss() {
        syncService.acknowledgeRejectedUpload()
    }
}
