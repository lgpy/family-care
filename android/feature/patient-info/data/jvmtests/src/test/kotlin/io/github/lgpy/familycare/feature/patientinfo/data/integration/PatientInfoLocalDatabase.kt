package io.github.lgpy.familycare.feature.patientinfo.data.integration

import com.powersync.PowerSyncDatabase
import com.powersync.db.crud.CrudTransaction
import com.powersync.encryption.JavaEncryptedDatabaseFactory
import com.powersync.encryption.Key
import io.github.lgpy.familycare.core.data.sync.CoreSyncedTables
import io.github.lgpy.familycare.core.data.sync.TableCatalog
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoSyncedTables
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * A real PowerSync database built from the **merged** schema — the core's tables
 * plus the patient-info module's — in a per-test temporary directory.
 *
 * That merge is the point: `TableCatalog` is what `DataLayer.create` builds at
 * start-up, so the module's repository runs against the same views and
 * `INSTEAD OF` triggers the phone gets, and a local `INSERT` on a view (which is
 * what PowerSync creates) is what these tests exercise.
 *
 * The file is encrypted with a throwaway passphrase, exactly like the phone's:
 * the JVM driver has no unencrypted factory. Nothing here reaches a network.
 */
internal class PatientInfoLocalDatabase(
    directory: Path,
    /** The signed-in user. */
    val userId: String = DEFAULT_USER_ID,
    /** The current circle. */
    val circleId: String = DEFAULT_CIRCLE_ID,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: PowerSyncDatabase = PowerSyncDatabase(
        factory = JavaEncryptedDatabaseFactory(Key.Passphrase(PASSPHRASE)),
        schema = TableCatalog(listOf(CoreSyncedTables, PatientInfoSyncedTables)).schema,
        dbFilename = DATABASE_FILE,
        scope = scope,
        dbDirectory = directory.toAbsolutePath().toString(),
    )

    /** Who is signed in, as the data layer would expose it. */
    val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(userId, null))

    /**
     * Seeds the circle and the signed-in user's membership, then drains the
     * queue: the seeded rows would otherwise be the first transaction a test sees.
     */
    suspend fun seedOwner(circleName: String = "Our family", role: String = OWNER) {
        seedCircle(circleName = circleName)
        seedMembership(userId = userId, role = role)
        database.drainUploadQueue()
    }

    suspend fun seedCircle(circleId: String = this.circleId, circleName: String = "Our family") {
        database.execute(
            INSERT_CIRCLE,
            listOf(circleId, circleName, EARLY, EARLY),
        )
    }

    suspend fun seedMembership(
        userId: String,
        role: String,
        createdAt: String = FIRST_MEMBERSHIP_AT,
        membershipId: String = "membership-$userId",
        circleId: String = this.circleId,
    ) {
        database.execute(
            INSERT_MEMBERSHIP,
            listOf(membershipId, circleId, userId, role, createdAt, createdAt),
        )
    }

    suspend fun seedPatient(
        patientId: String,
        displayName: String = "Grandma",
        circleId: String = this.circleId,
    ) {
        database.execute(
            INSERT_PATIENT,
            listOf(patientId, circleId, displayName, EARLY, EARLY),
        )
    }

    override fun close() {
        runBlocking { database.close() }
        scope.cancel()
    }

    internal companion object {
        const val DEFAULT_USER_ID = "11111111-1111-4111-8111-111111111111"
        const val DEFAULT_CIRCLE_ID = "22222222-2222-4222-8222-222222222222"

        const val OWNER = "owner"
        const val CAREGIVER = "caregiver"
        const val VIEWER = "viewer"

        /** The oldest membership, so the "current circle" rule picks this circle. */
        private const val FIRST_MEMBERSHIP_AT = "2026-11-18T09:00:00.000Z"
        private const val EARLY = "2026-11-18T08:00:00.000Z"

        /**
         * A fresh directory under the module's `build/`. Not `@TempDir`: on
         * Windows the SQLite file is still held for a moment after `close()`,
         * which fails JUnit's cleanup; Gradle owns (and cleans) `build/`.
         */
        fun testDirectory(name: String): Path = Files.createDirectories(
            Path.of("build", "tmp", "patient-info-powersync-tests", "$name-${UUID.randomUUID()}"),
        )

        private const val PASSPHRASE = "integration-test-database-key"
        private const val DATABASE_FILE = "familycare-patient-info-jvm-test.db"

        private const val INSERT_CIRCLE = """
            INSERT INTO care_circles (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)
        """

        private const val INSERT_MEMBERSHIP = """
            INSERT INTO memberships (id, circle_id, user_id, role, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """

        private const val INSERT_PATIENT = """
            INSERT INTO patients (
                id, circle_id, display_name,
                reminder_policy, escalation_delay_minutes, missed_window_minutes,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'primary_with_escalation', 30, 120, ?, ?)
        """
    }
}

/** Takes the oldest queued transaction, or fails the test when there is none. */
internal suspend fun PowerSyncDatabase.nextTransaction(): CrudTransaction =
    checkNotNull(getNextCrudTransaction()) { "Expected a queued CRUD transaction" }

/** Completes every queued transaction, so seeded rows do not pollute an assertion. */
internal suspend fun PowerSyncDatabase.drainUploadQueue() {
    while (true) {
        val transaction = getNextCrudTransaction() ?: return
        transaction.complete(null)
    }
}
