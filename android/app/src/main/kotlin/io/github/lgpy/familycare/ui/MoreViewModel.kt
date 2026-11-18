package io.github.lgpy.familycare.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.github.lgpy.familycare.core.ui.locale.AppLanguage
import kotlinx.coroutines.launch

/**
 * More → Language (D8): the device language is set by the platform in the
 * screen; this view model stores the same choice on the user's profile, so the
 * server writes notifications in it (M4) and a new phone follows the account.
 *
 * "System default" is resolved to a concrete server code on the device first:
 * `core.profiles.preferred_language` is `not null` and only accepts `pt-PT` and
 * `en` (see [AppLanguage.serverLanguage]).
 */
class MoreViewModel(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    /**
     * Saves the language that [language] means on this device
     * ([deviceLanguageTags], the platform's preferred languages).
     *
     * Best effort: the device language has already changed, so a profile row
     * that has not synced yet ([io.github.lgpy.familycare.core.domain.circle.CoreError.NotFound])
     * or an offline write must not undo or block the choice. The row syncs with
     * the account and is applied on the next device.
     */
    fun onLanguageChosen(language: AppLanguage, deviceLanguageTags: List<String>) {
        val serverLanguage = language.serverLanguage(deviceLanguageTags)
        viewModelScope.launch {
            profileRepository.updatePreferredLanguage(serverLanguage)
        }
    }
}
