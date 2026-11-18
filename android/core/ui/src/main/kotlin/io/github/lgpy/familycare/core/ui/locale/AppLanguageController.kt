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
 * Since D8/D23 the language is also stored on the user's profile
 * (`core.profiles.preferred_language`) by the More screen. This controller
 * only owns the *device* half: the platform locale, plus a small flag saying
 * whether someone chose a language in this app. Until they do, the language
 * that arrives on the profile is applied (a new phone follows the account); an
 * explicit choice — including "System default" — is never overridden.
 */
class AppLanguageController(context: Context) {
    private val localeManager: LocaleManager =
        context.getSystemService(LocaleManager::class.java)

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun current(): AppLanguage {
        val locales = localeManager.applicationLocales ?: LocaleList.getEmptyLocaleList()
        return AppLanguage.fromLanguageTags(
            List(locales.size()) { index -> locales[index].toLanguageTag() },
        )
    }

    /** Whether a language was chosen on this device (in this app). */
    fun hasExplicitChoice(): Boolean = preferences.getBoolean(KEY_EXPLICIT_CHOICE, false)

    /** Applies a language the user picked in the app; remembers that they did. */
    fun set(language: AppLanguage) {
        apply(language)
        preferences.edit().putBoolean(KEY_EXPLICIT_CHOICE, true).apply()
    }

    private fun apply(language: AppLanguage) {
        localeManager.applicationLocales = when (language) {
            AppLanguage.System -> LocaleList.getEmptyLocaleList()
            else -> LocaleList.forLanguageTags(checkNotNull(language.languageTag))
        }
    }

    private companion object {
        const val PREFERENCES = "app_language"
        const val KEY_EXPLICIT_CHOICE = "explicit_choice"
    }
}
