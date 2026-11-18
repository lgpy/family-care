package io.github.lgpy.familycare.ui.profile

import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.github.lgpy.familycare.core.ui.locale.AppLanguage
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import org.koin.compose.koinInject

/**
 * Keeps the profile's language (D8) in step with what this phone shows, while
 * the user has made no explicit in-app choice: the phone's language is written
 * to the profile, so server notifications (M4) arrive in the language the user
 * actually sees.
 *
 * It deliberately does NOT apply the profile's language to the phone. Every
 * profile starts as `pt-PT` (the column default), so applying it would switch an
 * English phone to Portuguese without the user asking. A choice made in More is
 * remembered on the device ([AppLanguageController.hasExplicitChoice]) and is
 * written to the profile by [io.github.lgpy.familycare.ui.MoreViewModel].
 */
@Composable
fun ProfileLanguageSync() {
    val profileRepository = koinInject<ProfileRepository>()
    val languageController = koinInject<AppLanguageController>()
    val profile by profileRepository.myProfile.collectAsState(initial = null)

    val preferredLanguage = profile?.preferredLanguage
    LaunchedEffect(preferredLanguage) {
        // Nothing to compare against until the profile row has synced.
        if (preferredLanguage == null) return@LaunchedEffect
        if (languageController.hasExplicitChoice()) return@LaunchedEffect

        val locales = LocaleList.getDefault()
        val deviceTags = List(locales.size()) { index -> locales[index].toLanguageTag() }
        val shown = AppLanguage.System.serverLanguage(deviceTags)
        if (shown != preferredLanguage) {
            // Best effort, like MoreViewModel: a failed local write changes nothing visible.
            profileRepository.updatePreferredLanguage(shown)
        }
    }
}
