package io.github.lgpy.familycare.ui.profile

import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.CoreUnitResult
import io.github.lgpy.familycare.core.domain.circle.Profile
import io.github.lgpy.familycare.ui.FakeProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** More → Your name: the current name, validation, and a local-first save. */
@OptIn(ExperimentalCoroutinesApi::class)
class YourNameViewModelTest {

    private lateinit var profiles: FakeProfileRepository
    private lateinit var viewModel: YourNameViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        profiles = FakeProfileRepository()
        profiles.profileFlow.value = Profile(
            id = "u1",
            displayName = "Miguel",
            preferredLanguage = "pt-PT",
        )
        viewModel = YourNameViewModel(profiles)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theCurrentNameIsLoaded() {
        assertTrue(viewModel.uiState.value.loaded)
        assertEquals("Miguel", viewModel.uiState.value.name)
    }

    @Test
    fun savingTrimsTheNameAndReportsSuccess() = runTest {
        viewModel.onNameChanged("  Miguel Silva  ")

        viewModel.save()

        assertEquals(listOf("Miguel Silva"), profiles.updates)
        assertTrue(viewModel.uiState.value.saved)
        assertEquals("Miguel Silva", viewModel.uiState.value.name)
    }

    @Test
    fun ablankNameIsRejectedBeforeTheCall() = runTest {
        viewModel.onNameChanged("   ")

        viewModel.save()

        assertEquals(YourNameError.InvalidName, viewModel.uiState.value.error)
        assertTrue(profiles.updates.isEmpty())
    }

    @Test
    fun aProfileThatHasNotSyncedSaysSo() = runTest {
        profiles.updateResult = CoreResult.Failure(CoreError.NotFound)

        viewModel.onNameChanged("Miguel")
        viewModel.save()

        assertEquals(YourNameError.NotSynced, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.saved)
    }

    @Test
    fun offlineShowsTheOfflineMessage() = runTest {
        profiles.updateResult = CoreResult.Failure(CoreError.Offline)

        viewModel.onNameChanged("Miguel")
        viewModel.save()

        assertEquals(YourNameError.Offline, viewModel.uiState.value.error)
    }

    @Test
    fun editingClearsTheLastMessage() = runTest {
        profiles.updateResult = CoreResult.Failure(CoreError.Offline)
        viewModel.onNameChanged("Miguel")
        viewModel.save()

        viewModel.onNameChanged("Miguel S")

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun errorsMapToTheMessagesTheScreenCanShow() {
        assertEquals(YourNameError.Offline, CoreError.Offline.toYourNameError())
        assertEquals(YourNameError.NotSynced, CoreError.NotFound.toYourNameError())
        assertEquals(YourNameError.NotAllowed, CoreError.NotAllowed.toYourNameError())
        assertEquals(YourNameError.Unknown, CoreError.Unknown.toYourNameError())
        val success: CoreUnitResult = CoreResult.Success(Unit)
        assertTrue(success is CoreResult.Success)
    }
}
