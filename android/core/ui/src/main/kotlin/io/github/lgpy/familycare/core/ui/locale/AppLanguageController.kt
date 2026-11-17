package io.github.lgpy.familycare.core.ui.locale

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * Reads and writes the per-app language through the platform [LocaleManager]
 * (minSdk 33, so AppCompat is not needed).
 *
 * The Android 13+ system stores this per app, which also means the choice
 * survives process death and appears in Android's per-app language settings
 * (see the generated locale config in the app module).
 *
 * Device-local only for M1.
 * TODO(M2): persist the per-user language on the identity profile server-side
 * once the identity service exists, so every caregiver's phone can follow
 * their own account instead of their phone.
 */
class AppLanguageController(context: Context) {
    private val localeManager: LocaleManager =
        context.getSystemService(LocaleManager::class.java)

    fun current(): AppLanguage {
        val locales = localeManager.applicationLocales ?: LocaleList.getEmptyLocaleList()
        return AppLanguage.fromLanguageTags(
            List(locales.size()) { index -> locales[index].toLanguageTag() },
        )
    }

    fun set(language: AppLanguage) {
        localeManager.applicationLocales = when (language) {
            AppLanguage.System -> LocaleList.getEmptyLocaleList()
            else -> LocaleList.forLanguageTags(checkNotNull(language.languageTag))
        }
    }
}
