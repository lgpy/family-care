package io.github.lgpy.familycare.core.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    @Test
    fun emptyPerAppLocaleFollowsTheSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromLanguageTags(emptyList()))
    }

    @Test
    fun portugueseMapsToEuropeanPortuguese() {
        assertEquals(
            AppLanguage.PortuguesePortugal,
            AppLanguage.fromLanguageTags(listOf("pt-PT")),
        )
        assertEquals(
            AppLanguage.PortuguesePortugal,
            AppLanguage.fromLanguageTags(listOf("pt")),
        )
    }

    @Test
    fun englishMapsToEnglish() {
        assertEquals(AppLanguage.English, AppLanguage.fromLanguageTags(listOf("en")))
        assertEquals(AppLanguage.English, AppLanguage.fromLanguageTags(listOf("en-GB")))
    }

    @Test
    fun unsupportedLocaleFallsBackToFollowingTheSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromLanguageTags(listOf("de-DE")))
    }

    @Test
    fun onlyTheFirstLocaleIsConsidered() {
        assertEquals(
            AppLanguage.English,
            AppLanguage.fromLanguageTags(listOf("en-GB", "pt-PT")),
        )
    }
}
