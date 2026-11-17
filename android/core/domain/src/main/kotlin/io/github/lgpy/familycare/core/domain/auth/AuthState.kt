package io.github.lgpy.familycare.core.domain.auth

/**
 * Who is using the app, as far as the data layer knows (D12).
 *
 * Pure Kotlin on purpose: the sign-in gate, the Settings account section and
 * the unit tests all read this type without depending on Supabase, Android or
 * any DI framework.
 */
sealed interface AuthState {

    /** The stored session is being loaded; nothing can be shown yet. */
    data object Loading : AuthState

    /** No Supabase session: the sign-in screen is shown. */
    data object SignedOut : AuthState

    /**
     * A session exists.
     *
     * @param userId Supabase user id (`sub`), stable across sign-ins.
     * @param email The Google account's email address, when Supabase returned it.
     */
    data class SignedIn(
        val userId: String,
        val email: String?,
    ) : AuthState
}
