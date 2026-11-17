package io.github.lgpy.familycare.ui

// M1 probe: this view model only drives the throwaway "Sync test" screen, used
// to run M1's done-test (a note added in airplane mode appears on the other
// phone after reconnecting). Delete it in M2 together with core.sync_probe and
// SyncProbeRepository.

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.sync.ProbeNote
import io.github.lgpy.familycare.core.domain.sync.SyncProbeRepository
import io.github.lgpy.familycare.core.domain.sync.SyncState
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The connection as the probe screen shows it. [SyncState] carries the raw
 * PowerSync flags; this collapses them to the three states a person can read,
 * so the mapping is unit-tested without a device or resources.
 */
enum class SyncTestStatus { Connected, Connecting, Offline }

/**
 * Everything the "Sync test" screen draws: the sync state, the notes, and the
 * note being typed.
 *
 * @param draft The text field's content, owned here so adding a note can clear
 *   it without waiting for the local write.
 * @param canAdd [draft] passes the domain's own rule (trim, 1..500 chars).
 */
data class SyncTestUiState(
    val status: SyncTestStatus = SyncTestStatus.Offline,
    val uploading: Boolean = false,
    val downloading: Boolean = false,
    val hasSynced: Boolean = false,
    val lastSyncedAt: Instant? = null,
    val hasError: Boolean = false,
    val notes: List<ProbeNote> = emptyList(),
    val draft: String = "",
    val canAdd: Boolean = false,
)

/**
 * Drives the M1 probe screen (see the file comment).
 *
 * Two sources feed one state: [SyncService.state] for the status card and
 * [SyncProbeRepository.notes] for the list. Add and delete write to the local
 * database through the repository in [viewModelScope]; neither waits for the
 * network (D3), so the screen works the same in airplane mode.
 */
class SyncTestViewModel(private val syncService: SyncService) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncTestUiState())
    val uiState: StateFlow<SyncTestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            syncService.state.collect { sync ->
                _uiState.update {
                    it.copy(
                        status = sync.toStatus(),
                        uploading = sync.uploading,
                        downloading = sync.downloading,
                        hasSynced = sync.hasSynced == true,
                        lastSyncedAt = sync.lastSyncedAt,
                        hasError = sync.hasError,
                    )
                }
            }
        }
        viewModelScope.launch {
            // The repository already emits newest first.
            syncService.probeNotes.notes.collect { notes ->
                _uiState.update { it.copy(notes = notes) }
            }
        }
    }

    fun onDraftChange(text: String) {
        _uiState.update {
            it.copy(draft = text, canAdd = SyncProbeRepository.isValidText(text))
        }
    }

    fun onAdd() {
        val text = _uiState.value.draft.trim()
        // The domain rule and the database constraint are the same 1..500.
        if (!SyncProbeRepository.isValidText(text)) return

        // Clear first: the write below is local-only and must not make the
        // field wait for the network.
        _uiState.update { it.copy(draft = "", canAdd = false) }
        viewModelScope.launch {
            // Probe: a failed local write must not take the app down, and the
            // note simply does not appear.
            runCatching { syncService.probeNotes.add(text) }
        }
    }

    fun onDelete(id: String) {
        viewModelScope.launch {
            runCatching { syncService.probeNotes.delete(id) }
        }
    }
}

private fun SyncState.toStatus(): SyncTestStatus = when {
    connected -> SyncTestStatus.Connected
    connecting -> SyncTestStatus.Connecting
    else -> SyncTestStatus.Offline
}
