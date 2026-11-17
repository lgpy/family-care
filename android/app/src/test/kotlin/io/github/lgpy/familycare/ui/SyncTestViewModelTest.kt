package io.github.lgpy.familycare.ui

// M1 probe test (delete in M2 with SyncTestViewModel): the screen's logic
// against fakes, no PowerSync, no database and no network.

import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.sync.ProbeNote
import io.github.lgpy.familycare.core.domain.sync.SyncProbeRepository
import io.github.lgpy.familycare.core.domain.sync.SyncState
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTestViewModelTest {

    private lateinit var syncService: FakeSyncService
    private lateinit var notes: FakeProbeRepository
    private lateinit var viewModel: SyncTestViewModel

    @Before
    fun setUp() {
        // Eager main dispatcher: the state collectors and the add/delete
        // coroutines run as soon as they are launched, so each test can assert
        // straight after the call.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        notes = FakeProbeRepository()
        syncService = FakeSyncService(state = MutableStateFlow(SyncState()), probeNotes = notes)
        viewModel = SyncTestViewModel(syncService)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsOfflineWithNoNotesAndAnEmptyDraft() {
        val state = viewModel.uiState.value

        assertEquals(SyncTestStatus.Offline, state.status)
        assertTrue(state.notes.isEmpty())
        assertEquals("", state.draft)
        assertFalse(state.canAdd)
        assertFalse(state.hasSynced)
        assertNull(state.lastSyncedAt)
        assertFalse(state.hasError)
    }

    @Test
    fun addingTrimsTheNoteAndClearsTheField() = runTest {
        viewModel.onDraftChange("  Milk at 8  ")
        assertTrue(viewModel.uiState.value.canAdd)

        viewModel.onAdd()

        assertEquals(listOf("Milk at 8"), notes.added)
        assertEquals("", viewModel.uiState.value.draft)
    }

    @Test
    fun aBlankDraftCannotBeAdded() = runTest {
        viewModel.onDraftChange("   ")
        assertFalse(viewModel.uiState.value.canAdd)

        viewModel.onAdd()

        assertTrue(notes.added.isEmpty())
    }

    @Test
    fun aDraftOverTheLimitCannotBeAdded() = runTest {
        val tooLong = "x".repeat(SyncProbeRepository.MAX_TEXT_LENGTH + 1)
        viewModel.onDraftChange(tooLong)
        assertFalse(viewModel.uiState.value.canAdd)

        viewModel.onAdd()

        assertTrue(notes.added.isEmpty())
        // The field keeps what was typed, so the text can be shortened.
        assertEquals(tooLong, viewModel.uiState.value.draft)
    }

    @Test
    fun aDraftAtTheLimitIsAccepted() = runTest {
        viewModel.onDraftChange("x".repeat(SyncProbeRepository.MAX_TEXT_LENGTH))
        assertTrue(viewModel.uiState.value.canAdd)
    }

    @Test
    fun deleteForwardsTheId() = runTest {
        viewModel.onDelete("note-2")

        assertEquals(listOf("note-2"), notes.deleted)
    }

    @Test
    fun connectingMapsToConnecting() {
        syncService.state.value = SyncState(connecting = true)

        assertEquals(SyncTestStatus.Connecting, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.hasSynced)
    }

    @Test
    fun aReadyConnectionMapsToConnected() {
        syncService.state.value = SyncState(connected = true)

        assertEquals(SyncTestStatus.Connected, viewModel.uiState.value.status)
    }

    @Test
    fun theFirstCompletedSyncCarriesItsTime() {
        val syncedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

        syncService.state.value = SyncState(
            connected = true,
            hasSynced = true,
            lastSyncedAt = syncedAt,
        )

        val state = viewModel.uiState.value
        assertEquals(SyncTestStatus.Connected, state.status)
        assertTrue(state.hasSynced)
        assertEquals(syncedAt, state.lastSyncedAt)
    }

    @Test
    fun uploadDownloadAndErrorFlagsAreExposed() {
        syncService.state.value = SyncState(
            connected = true,
            uploading = true,
            downloading = true,
            hasError = true,
        )

        val state = viewModel.uiState.value
        assertTrue(state.uploading)
        assertTrue(state.downloading)
        assertTrue(state.hasError)
    }

    @Test
    fun theRepositoryNotesAreExposedNewestFirst() {
        val newer = ProbeNote(
            id = "note-2",
            text = "Newer",
            createdAt = "2026-01-02T10:00:00.000Z",
            updatedAt = null,
        )
        val older = ProbeNote(
            id = "note-1",
            text = "Older",
            createdAt = "2026-01-01T10:00:00.000Z",
            updatedAt = null,
        )

        notes.notesState.value = listOf(newer, older)

        assertEquals(listOf(newer, older), viewModel.uiState.value.notes)
    }

    private class FakeSyncService(
        override val state: MutableStateFlow<SyncState>,
        override val probeNotes: FakeProbeRepository,
    ) : SyncService {
        override fun start() = Unit
    }

    private class FakeProbeRepository : SyncProbeRepository {

        val notesState = MutableStateFlow<List<ProbeNote>>(emptyList())
        override val notes: Flow<List<ProbeNote>> = notesState

        val added = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override suspend fun add(text: String) {
            added += text
        }

        override suspend fun delete(id: String) {
            deleted += id
            notesState.update { notes -> notes.filterNot { it.id == id } }
        }
    }
}
