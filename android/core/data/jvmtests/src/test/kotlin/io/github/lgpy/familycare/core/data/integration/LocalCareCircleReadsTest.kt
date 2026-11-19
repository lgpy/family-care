package io.github.lgpy.familycare.core.data.integration

import io.github.lgpy.familycare.core.data.circle.CareCircleRpc
import io.github.lgpy.familycare.core.data.circle.PowerSyncCareCircleRepository
import io.github.lgpy.familycare.core.data.circle.PowerSyncPatientRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CircleSetupState
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.sync.SyncState
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The read half of the M2 repositories against a real PowerSync database: the
 * "current circle", member and patient queries that the screens collect.
 *
 * Rows are seeded as a sync download would leave them (rows in the same views
 * and tables), so an SQL error, a wrong join or a wrong `ORDER BY` fails here.
 */
class LocalCareCircleReadsTest {

    private lateinit var directory: Path

    private lateinit var local: LocalDatabase
    private lateinit var repository: PowerSyncCareCircleRepository
    private lateinit var patients: PowerSyncPatientRepository
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val syncState = MutableStateFlow(SyncState(hasSynced = true))

    @BeforeEach
    fun setUp() = runBlocking {
        directory = LocalDatabase.testDirectory("local")
        local = LocalDatabase(directory)
        local.seedOwner(circleName = "Our family")
        // A second member, with a profile that synced after the membership.
        local.seedMembership(userId = OTHER_USER, role = LocalDatabase.CAREGIVER)
        local.seedProfile(local.userId, "Ana")
        local.seedProfile(OTHER_USER, "Bruno")
        // Two active patients (sorted case-insensitively by name) and one archived.
        local.seedPatient(patientId = "aaaaaaaa-0000-4000-8000-000000000001", displayName = "Zeca")
        local.seedPatient(patientId = "aaaaaaaa-0000-4000-8000-000000000002", displayName = "alice")
        local.seedPatient(
            patientId = "aaaaaaaa-0000-4000-8000-000000000003",
            displayName = "Archived",
            archivedAt = "2026-11-18T11:00:00.000Z",
        )
        local.database.drainUploadQueue()

        authState.value = AuthState.SignedIn(local.userId, "ana@example.test")
        repository = PowerSyncCareCircleRepository(
            database = local.database,
            authState = authState,
            syncState = syncState,
            rpc = UnusedRpc,
        )
        patients = PowerSyncPatientRepository(local.database, authState, UnusedPatientRpc)
    }

    @AfterEach
    fun tearDown() {
        local.close()
    }

    @Test
    fun `the current circle and role come from the oldest membership`() = runBlocking {
        val circle = withTimeout(READ_TIMEOUT) { repository.circle.first { it != null } }!!
        assertEquals(local.circleId, circle.id.value)
        assertEquals("Our family", circle.name)

        assertEquals(MemberRole.OWNER, withTimeout(READ_TIMEOUT) { repository.myRole.first { it != null } })

        assertEquals(
            CircleSetupState.HasCircle(circle, MemberRole.OWNER),
            withTimeout(READ_TIMEOUT) { repository.setupState.first { it is CircleSetupState.HasCircle } },
        )
    }

    @Test
    fun `members are the circle's memberships joined with their profile names`() = runBlocking {
        val members = withTimeout(READ_TIMEOUT) {
            repository.members.first { it.size == 2 }
        }

        // Owner first, then name order: the order the family list shows.
        assertEquals(listOf("Ana", "Bruno"), members.map { it.displayName })
        assertEquals(listOf(MemberRole.OWNER, MemberRole.CAREGIVER), members.map { it.role })
        assertTrue(members[0].isMe)
        assertFalse(members[1].isMe)
        assertEquals(local.userId, members[0].userId)
        assertEquals("membership-${local.userId}", members[0].membershipId.value)
    }

    @Test
    fun `the patient list is the circle's active patients by name`() = runBlocking {
        val list = withTimeout(READ_TIMEOUT) {
            patients.patients.first { it.size == 2 }
        }

        assertEquals(listOf("alice", "Zeca"), list.map { it.displayName })
        assertTrue(list.all { it.circleId.value == local.circleId })
        assertTrue(list.none { it.isArchived })
    }

    /** The online half is irrelevant to these reads; failing loudly keeps it so. */
    private object UnusedRpc : CareCircleRpc {
        override suspend fun createCircle(name: String): String = unused()

        override suspend fun acceptInvitation(normalizedCode: String): String = unused()

        override suspend fun createInvitation(
            circleId: String,
            email: String,
            role: MemberRole,
        ): String = unused()

        override suspend fun listInvitations(circleId: String): List<Invitation> = unused()

        override suspend fun revokeInvitation(invitationId: String): Unit = unused()

        override suspend fun removeMember(membershipId: String): Unit = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("read-only test")
    }

    private companion object {
        const val OTHER_USER = "11111111-1111-4111-8111-222222222222"
        val READ_TIMEOUT = 15.seconds
    }
}
