package io.github.lgpy.familycare.core.domain.circle

import kotlinx.coroutines.flow.Flow

/**
 * The signed-in user's own profile (name, language).
 *
 * The row is created server-side when the account is created and arrives through
 * the sync stream once the user belongs to a circle (the `profiles` stream
 * covers co-members, which includes the user themselves). It is null before
 * that first sync, and stays null for an account that has not joined a circle
 * yet.
 */
interface ProfileRepository {

    /**
     * The signed-in user's profile, or null while it has not synced (or nobody
     * is signed in). Emits again when the row changes.
     */
    val myProfile: Flow<Profile?>

    /**
     * Changes the signed-in user's display name, locally first: the new name
     * shows immediately and is uploaded with the next sync.
     *
     * @param displayName 1..100 characters after trimming ([Profile.isValidDisplayName]).
     * @return success, or [CoreError.Invalid] with [CoreField.PROFILE_DISPLAY_NAME],
     *   [CoreError.NotFound] when the profile row has not synced yet (the UPDATE
     *   would silently do nothing), [CoreError.NotAllowed] when signed out.
     */
    suspend fun updateDisplayName(displayName: String): CoreResult<Unit>
}
