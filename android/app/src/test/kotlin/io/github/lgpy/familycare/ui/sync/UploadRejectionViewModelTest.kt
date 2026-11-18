package io.github.lgpy.familycare.ui.sync

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.sync.RejectedChange
import io.github.lgpy.familycare.core.domain.sync.RejectedUpload
import io.github.lgpy.familycare.core.domain.sync.RejectionReason
import io.github.lgpy.familycare.core.domain.sync.SyncState
import io.github.lgpy.familycare.ui.FakeSyncService
import io.github.lgpy.familycare.ui.awaitWhere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The app-shell message: only a refused and discarded change is shown (never a
 * transient error), a successful upload does not hide it, and dismissing clears
 * it through the sync service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadRejectionViewModelTest {

    private lateinit var syncService: FakeSyncService
    private lateinit var viewModel: UploadRejectionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        syncService = FakeSyncService()
        viewModel = UploadRejectionViewModel(syncService)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aRefusedChangeIsShown() = runTest {
        viewModel.rejection.test {
            assertNull(awaitItem())

            syncService.stateFlow.value = SyncState(connected = true, rejectedUpload = refusal)

            assertEquals(refusal, awaitWhere { it != null })
        }
    }

    @Test
    fun aTransientSyncErrorShowsNothing() = runTest {
        viewModel.rejection.test {
            assertNull(awaitItem())

            // Offline or retrying: hasError but nothing was refused, so no
            // alarming "your change was undone" message.
            syncService.stateFlow.value = SyncState(
                connected = false,
                hasError = true,
                rejectedUpload = null,
            )

            expectNoEvents()
        }
    }

    @Test
    fun aLaterSuccessDoesNotHideAnUnacknowledgedRefusal() = runTest {
        syncService.stateFlow.value = SyncState(connected = true, rejectedUpload = refusal)

        viewModel.rejection.test {
            assertEquals(refusal, awaitWhere { it != null })

            // The queue moved on and the next upload succeeded.
            syncService.stateFlow.value = SyncState(
                connected = true,
                uploading = true,
                hasError = false,
                rejectedUpload = refusal,
            )

            expectNoEvents()
            assertEquals(refusal, viewModel.rejection.value)
        }
    }

    @Test
    fun dismissingAcknowledgesAndClearsIt() = runTest {
        syncService.stateFlow.value = SyncState(connected = true, rejectedUpload = refusal)

        viewModel.rejection.test {
            assertEquals(refusal, awaitWhere { it != null })

            viewModel.dismiss()

            assertNull(awaitWhere { it == null })
        }

        assertEquals(1, syncService.acknowledgedRejections)
        assertNull(syncService.stateFlow.value.rejectedUpload)
    }

    private companion object {
        val refusal = RejectedUpload(
            change = RejectedChange.PATIENT,
            reason = RejectionReason.NOT_ALLOWED,
        )
    }
}
