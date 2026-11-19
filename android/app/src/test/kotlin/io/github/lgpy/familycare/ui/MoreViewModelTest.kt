package io.github.lgpy.familycare.ui

import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.ui.locale.AppLanguage
import io.github.lgpy.familycare.core.ui.status.MoreStatus
import io.github.lgpy.familycare.core.ui.status.MoreStatusSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** More → Language (D8) and the reminder status contributed by medications. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoreViewModelTest {

    private lateinit var profiles: FakeProfileRepository
    private lateinit var statuses: FakeMoreStatusSource
    private lateinit var viewModel: MoreViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        profiles = FakeProfileRepository()
        statuses = FakeMoreStatusSource()
        viewModel = MoreViewModel(profiles, statuses)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aChosenLanguageIsSavedAsItsServerCode() {
        viewModel.onLanguageChosen(
            AppLanguage.PortuguesePortugal,
            deviceLanguageTags = listOf("en-GB"),
        )

        assertEquals(listOf("pt-PT"), profiles.languageUpdates)
    }

    @Test
    fun systemDefaultSavesTheResolvedDeviceLanguage() {
        viewModel.onLanguageChosen(AppLanguage.System, deviceLanguageTags = listOf("pt-PT"))
        viewModel.onLanguageChosen(AppLanguage.System, deviceLanguageTags = listOf("de-DE"))

        // The profile column only accepts the two server codes; an unsupported
        // phone language means the app's English fallback.
        assertEquals(listOf("pt-PT", "en"), profiles.languageUpdates)
    }

    @Test
    fun aProfileThatHasNotSyncedDoesNotCrashTheScreen() {
        profiles.languageUpdateResult = CoreResult.Failure(CoreError.NotFound)

        viewModel.onLanguageChosen(AppLanguage.English, deviceLanguageTags = emptyList())

        assertEquals(listOf("en"), profiles.languageUpdates)
    }

    @Test
    fun aHealthyPhoneShowsNoProblem() {
        statuses.status = MoreStatus(problems = 0)

        viewModel.refreshReminderStatus()

        val status = viewModel.reminderStatus.value
        assertEquals(0, status?.problems)
        assertFalse(status?.needsAttention == true)
    }

    @Test
    fun eachHealthProblemBecomesAnAttentionRow() {
        statuses.status = MoreStatus(problems = 3)

        viewModel.refreshReminderStatus()

        assertEquals(3, viewModel.reminderStatus.value?.problems)
        assertTrue(viewModel.reminderStatus.value?.needsAttention == true)
    }

    @Test
    fun anUnavailableCheckFallsBackToNoStatus() {
        statuses.status = null

        viewModel.refreshReminderStatus()

        assertNull(viewModel.reminderStatus.value)
    }
}

/** A feature stub for the core status contract. */
private class FakeMoreStatusSource(
    var status: MoreStatus? = MoreStatus(problems = 0),
) : MoreStatusSource {

    override suspend fun reminders(): MoreStatus? = status
}
