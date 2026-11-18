package io.github.lgpy.familycare.core.data.integration

import com.powersync.db.crud.UpdateType
import io.github.lgpy.familycare.core.data.circle.PowerSyncProfileRepository
import io.github.lgpy.familycare.core.data.sync.PROFILES_TABLE
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PowerSyncProfileRepository] against a real PowerSync database: the row the
 * server creates on signup is updated locally, and the change must be queued as
 * a PATCH on `profiles` (an INSERT would be refused by the server's grants).
 */
class LocalProfileRepositoryTest {

    private lateinit var directory: Path

    private lateinit var local: LocalDatabase
    private lateinit var repository: PowerSyncProfileRepository
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)

    @BeforeEach
    fun setUp() = runBlocking {
        directory = LocalDatabase.testDirectory("local")
        local = LocalDatabase(directory)
        local.seedOwner()
        local.seedProfile(local.userId, "Ana")
        local.database.drainUploadQueue()
        authState.value = AuthState.SignedIn(local.userId, "ana@example.test")
        repository = PowerSyncProfileRepository(local.database, authState)
    }

    @AfterEach
    fun tearDown() {
        local.close()
    }

    @Test
    fun `updating the display name changes the local row and queues a PATCH`() = runBlocking {
        assertEquals(CoreResult.Success(Unit), repository.updateDisplayName("  Ana Maria  "))

        val profile = withTimeout(READ_TIMEOUT) {
            repository.myProfile.first { it?.displayName == "Ana Maria" }
        }!!
        assertEquals(local.userId, profile.id)
        assertEquals("pt-PT", profile.preferredLanguage)

        val entry = local.database.nextTransaction().crud.single()
        assertEquals(UpdateType.PATCH, entry.op)
        assertEquals(PROFILES_TABLE, entry.table)
        assertEquals(local.userId, entry.id)
        assertEquals(
            JsonObject(mapOf("display_name" to JsonPrimitive("Ana Maria"))),
            entry.opData!!.jsonValues,
        )
    }

    @Test
    fun `updating the preferred language changes the local row and queues a PATCH`() = runBlocking {
        assertEquals(CoreResult.Success(Unit), repository.updatePreferredLanguage("en"))

        val profile = withTimeout(READ_TIMEOUT) {
            repository.myProfile.first { it?.preferredLanguage == "en" }
        }!!
        assertEquals(local.userId, profile.id)
        assertEquals("Ana", profile.displayName)

        val entry = local.database.nextTransaction().crud.single()
        assertEquals(UpdateType.PATCH, entry.op)
        assertEquals(PROFILES_TABLE, entry.table)
        assertEquals(local.userId, entry.id)
        assertEquals(
            JsonObject(mapOf("preferred_language" to JsonPrimitive("en"))),
            entry.opData!!.jsonValues,
        )
    }

    @Test
    fun `a language code the server does not accept is rejected before any write`() = runBlocking {
        assertEquals(
            CoreResult.Failure(
                CoreError.Invalid(setOf(CoreField.PROFILE_PREFERRED_LANGUAGE)),
            ),
            repository.updatePreferredLanguage("fr"),
        )
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `a profile row that has not synced is reported as not found and queues nothing`() = runBlocking {
        val emptyDirectory = LocalDatabase.testDirectory("empty")
        val empty = LocalDatabase(emptyDirectory)
        val emptyState = MutableStateFlow<AuthState>(AuthState.SignedIn(empty.userId, "ana@example.test"))
        val emptyRepository = PowerSyncProfileRepository(empty.database, emptyState)

        assertEquals(
            CoreResult.Failure(CoreError.NotFound),
            emptyRepository.updateDisplayName("Ana Maria"),
        )
        assertNull(empty.database.getNextCrudTransaction())
        assertNull(withTimeout(READ_TIMEOUT) { emptyRepository.myProfile.first() })

        empty.close()
    }

    private companion object {
        val READ_TIMEOUT = 15.seconds
    }
}
