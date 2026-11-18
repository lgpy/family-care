package io.github.lgpy.familycare.core.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun theProfileLanguageCodeMapsToALanguage() {
        assertEquals(
            AppLanguage.PortuguesePortugal,
            AppLanguage.fromServerLanguage("pt-PT"),
        )
        assertEquals(AppLanguage.English, AppLanguage.fromServerLanguage("en"))
        // A code this version does not know leaves the device choice alone.
        assertNull(AppLanguage.fromServerLanguage("fr"))
        assertNull(AppLanguage.fromServerLanguage(null))
    }

    @Test
    fun aChosenLanguageMapsToItsServerCode() {
        assertEquals(
            "pt-PT",
            AppLanguage.PortuguesePortugal.serverLanguage(listOf("en-GB")),
        )
        assertEquals("en", AppLanguage.English.serverLanguage(listOf("pt-PT")))
    }

    @Test
    fun systemDefaultResolvesToTheServerCodeForTheDeviceLanguage() {
        assertEquals("pt-PT", AppLanguage.System.serverLanguage(listOf("pt-PT")))
        assertEquals("pt-PT", AppLanguage.System.serverLanguage(listOf("pt")))
        assertEquals("en", AppLanguage.System.serverLanguage(listOf("en-GB")))
        // The profile column cannot be null, so the app's fallback is stored.
        assertEquals("en", AppLanguage.System.serverLanguage(listOf("de-DE")))
        assertEquals("en", AppLanguage.System.serverLanguage(emptyList()))
    }
}
