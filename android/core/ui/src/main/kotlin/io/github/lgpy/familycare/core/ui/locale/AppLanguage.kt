package io.github.lgpy.familycare.core.ui.locale

/**
 * The languages the app ships. [System] means "follow the device language";
 * if the device language is neither Portuguese nor English the app falls back
 * to English (the unqualified resources).
 */
enum class AppLanguage(val languageTag: String?) {
    System(null),
    PortuguesePortugal("pt-PT"),
    English("en"),
    ;

    /**
     * The `core.profiles.preferred_language` code for this choice on a device
     * whose preferred language tags are [deviceLanguageTags].
     *
     * The profile column is `not null` and only accepts the two server codes,
     * so "system default" is resolved on the phone: Portuguese stays `pt-PT`;
     * anything else becomes `en`, the app's fallback.
     */
    fun serverLanguage(deviceLanguageTags: List<String>): String = when (this) {
        PortuguesePortugal -> PORTUGUESE_TAG
        English -> ENGLISH_TAG
        System -> if (fromLanguageTags(deviceLanguageTags) == PortuguesePortugal) {
            PORTUGUESE_TAG
        } else {
            ENGLISH_TAG
        }
    }

    companion object {
        /** `profiles_preferred_language` values (see `Profile.LANGUAGES`). */
        const val PORTUGUESE_TAG: String = "pt-PT"
        const val ENGLISH_TAG: String = "en"

        /**
         * The language a profile's stored code names, or null when the code is
         * not one this app version knows (leave the device choice alone).
         */
        fun fromServerLanguage(code: String?): AppLanguage? = when (code) {
            PORTUGUESE_TAG -> PortuguesePortugal
            ENGLISH_TAG -> English
            else -> null
        }

        /**
         * Maps the platform's per-app locale list onto one of [AppLanguage].
         * An empty list means the app follows the system.
         */
        fun fromLanguageTags(tags: List<String>): AppLanguage {
            val first = tags.firstOrNull()?.trim()?.lowercase() ?: return System
            return when {
                first == "pt" || first.startsWith("pt-") -> PortuguesePortugal
                first == "en" || first.startsWith("en-") -> English
                else -> System
            }
        }
    }
}
