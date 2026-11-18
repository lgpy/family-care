package io.github.lgpy.familycare.core.domain.circle

/**
 * The public face of a user: name and language.
 *
 * `core.profiles` is auto-created from `auth.users` on signup, because
 * `auth.users` cannot be synced. Co-members read names from here
 * (see [CircleMember.displayName]).
 *
 * @param id The same uuid as `auth.users.id`.
 * @param preferredLanguage The server code, one of [LANGUAGES]. It is kept as a
 *   string on purpose: the UI already has its own language type
 *   (`core.ui.locale.AppLanguage`), and the domain module must stay pure Kotlin
 *   (D22).
 */
data class Profile(
    val id: String,
    val displayName: String,
    val preferredLanguage: String,
) {
    companion object {
        /** Mirrors `profiles_display_name_length` in the M2 migration. */
        const val MAX_DISPLAY_NAME_LENGTH: Int = 100

        /** Mirrors the `profiles_preferred_language` check. */
        val LANGUAGES: Set<String> = setOf("pt-PT", "en")

        /** The value [ProfileRepository.updateDisplayName] stores. */
        fun normalizeDisplayName(name: String): String = name.trim()

        /** Whether [name] is acceptable (1..100 characters after trimming). */
        fun isValidDisplayName(name: String): Boolean =
            normalizeDisplayName(name).length in 1..MAX_DISPLAY_NAME_LENGTH

        /** Whether [language] is a value of `profiles_preferred_language`. */
        fun isValidLanguage(language: String): Boolean = language in LANGUAGES
    }
}
