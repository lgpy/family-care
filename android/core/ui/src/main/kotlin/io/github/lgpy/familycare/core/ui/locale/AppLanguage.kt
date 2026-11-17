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

    companion object {
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
