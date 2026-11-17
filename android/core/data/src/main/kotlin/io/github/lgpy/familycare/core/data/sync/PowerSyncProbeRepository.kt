package io.github.lgpy.familycare.core.data.sync

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import com.powersync.db.getString
import com.powersync.db.getStringOptional
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.sync.ProbeNote
import io.github.lgpy.familycare.core.domain.sync.SyncProbeRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [SyncProbeRepository] over the local, encrypted PowerSync database.
 *
 * Reads and writes only touch SQLite, so they work in airplane mode: the insert
 * is recorded locally, shows up in [notes] immediately, and PowerSync uploads it
 * when the network returns (M1's "airplane-mode edit appears on the other
 * phone" test).
 *
 * `owner_id` is set from the signed-in user, and reads are filtered by it, so a
 * row that is still cached from a previous session can never appear under the
 * new user before the database is cleared.
 */
internal class PowerSyncProbeRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
    private val now: () -> Instant = { Clock.System.now() },
) : SyncProbeRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val notes: Flow<List<ProbeNote>> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                database.watch(SELECT_NOTES, listOf(userId)) { cursor -> cursor.toProbeNote() }
            }
        }

    override suspend fun add(text: String) {
        require(SyncProbeRepository.isValidText(text)) {
            "A note must be 1..${SyncProbeRepository.MAX_TEXT_LENGTH} characters"
        }
        val userId = currentUserId() ?: error("Cannot add a note while signed out")

        // `uuid()` is provided by the PowerSync SQLite build, so the id exists
        // offline. `updated_at` is left to the server trigger: a phone clock is
        // not trusted for it (see the migration).
        database.execute(
            sql = "INSERT INTO $PROBE_TABLE (id, owner_id, note, created_at) VALUES (uuid(), ?, ?, ?)",
            parameters = listOf(userId, text.trim(), now().toString()),
        )
    }

    override suspend fun delete(id: String) {
        database.execute(sql = "DELETE FROM $PROBE_TABLE WHERE id = ?", parameters = listOf(id))
    }

    private fun currentUserId(): String? = (authState.value as? AuthState.SignedIn)?.userId

    private fun SqlCursor.toProbeNote(): ProbeNote = ProbeNote(
        id = getString("id"),
        text = getString("note"),
        createdAt = getString("created_at"),
        updatedAt = getStringOptional("updated_at"),
    )

    private companion object {
        const val SELECT_NOTES =
            "SELECT id, note, created_at, updated_at FROM $PROBE_TABLE WHERE owner_id = ? " +
                "ORDER BY created_at DESC"
    }
}
