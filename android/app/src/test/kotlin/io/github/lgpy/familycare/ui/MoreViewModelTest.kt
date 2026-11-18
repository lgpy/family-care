package io.github.lgpy.familycare.ui

import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.ui.locale.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** More → Language: the chosen language is also stored on the profile (D8). */
@OptIn(ExperimentalCoroutinesApi::class)
class MoreViewModelTest {

    private lateinit var profiles: FakeProfileRepository
    private lateinit var viewModel: MoreViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        profiles = FakeProfileRepository()
        viewModel = MoreViewModel(profiles)
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
}
