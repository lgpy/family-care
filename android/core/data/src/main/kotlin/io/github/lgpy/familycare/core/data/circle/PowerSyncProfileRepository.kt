package io.github.lgpy.familycare.core.data.circle

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.Profile
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [ProfileRepository] over the local, encrypted PowerSync database.
 *
 * [updateDisplayName] writes the local row, so the change shows immediately and
 * the uploader sends `display_name` with the next sync. The row is created by
 * the server (`core.handle_new_user()`), never by the app: the client has no
 * INSERT grant, so an update of a missing row would be a permanent error. The
 * repository therefore reports [CoreError.NotFound] instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PowerSyncProfileRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
) : ProfileRepository {

    private val signedInUserId: Flow<String?> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    override val myProfile: Flow<Profile?> = signedInUserId.flatMapLatest { userId ->
        if (userId == null) {
            flowOf(null)
        } else {
            database.watch(SELECT_MY_PROFILE, listOf(userId)) { cursor ->
                Profile(
                    id = cursor.getString("id"),
                    displayName = cursor.getString("display_name"),
                    preferredLanguage = cursor.getString("preferred_language"),
                )
            }.map { it.firstOrNull() }
        }
    }

    override suspend fun updateDisplayName(displayName: String): CoreResult<Unit> {
        val normalized = Profile.normalizeDisplayName(displayName)
        if (!Profile.isValidDisplayName(normalized)) {
            return CoreResult.Failure(CoreError.Invalid(setOf(CoreField.PROFILE_DISPLAY_NAME)))
        }

        val userId = signedInUserId.first()
            ?: return CoreResult.Failure(CoreError.NotAllowed)

        val present = database.getOptional(SELECT_PROFILE_PRESENT, listOf(userId)) { cursor ->
            cursor.getString("id")
        } != null
        if (!present) {
            return CoreResult.Failure(CoreError.NotFound)
        }

        return try {
            database.execute(UPDATE_PROFILE_DISPLAY_NAME, listOf(normalized, userId))
            CoreResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            CoreResult.Failure(CoreError.Unknown)
        }
    }
}
