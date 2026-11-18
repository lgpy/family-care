package io.github.lgpy.familycare.core.data.integration

import com.powersync.PowerSyncDatabase
import com.powersync.db.crud.CrudTransaction
import com.powersync.encryption.JavaEncryptedDatabaseFactory
import com.powersync.encryption.Key
import io.github.lgpy.familycare.core.data.sync.CoreSchema
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * A real PowerSync database, built from the app's own [CoreSchema], in a
 * per-test temporary directory.
 *
 * This is the point of these tests: the repositories run the SQL that ships in
 * the app against the schema PowerSync itself creates (views plus `INSTEAD OF`
 * triggers, not plain tables), so a statement PowerSync's SQLite rejects — like
 * the `INSERT ... ON CONFLICT DO UPDATE` on a view that used to sit in
 * [io.github.lgpy.familycare.core.data.circle.PowerSyncPatientRepository] —
 * fails here instead of on a phone.
 *
 * The file is encrypted with a throwaway passphrase, exactly like the phone's:
 * the JVM driver has no unencrypted factory. Nothing here reaches a network.
 */
internal class LocalDatabase(
    directory: Path,
    /** The signed-in user; a real Supabase user id in the upload tests. */
    val userId: String = DEFAULT_USER_ID,
    /** The current circle; a real circle id in the upload tests. */
    val circleId: String = DEFAULT_CIRCLE_ID,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: PowerSyncDatabase = PowerSyncDatabase(
        factory = JavaEncryptedDatabaseFactory(Key.Passphrase(PASSPHRASE)),
        schema = CoreSchema,
        dbFilename = DATABASE_FILE,
        scope = scope,
        dbDirectory = directory.toAbsolutePath().toString(),
    )

    /**
     * Inserts the circle and the signed-in user's owner membership: what the
     * `care_circles` and `memberships` sync streams would have downloaded.
     *
     * Local writes are used rather than PowerSync's internal tables: the same
     * `INSTEAD OF` triggers the app's writes go through, so the seeded rows are
     * exactly the rows a sync would produce for the queries.
     */
    suspend fun seedOwner(circleName: String = "Our family") {
        seedCircle(circleName = circleName)
        seedMembership(userId = userId, role = OWNER, createdAt = FIRST_MEMBERSHIP_AT)
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
        createdAt: String = SECOND_MEMBERSHIP_AT,
        membershipId: String = "membership-$userId",
        circleId: String = this.circleId,
    ) {
        database.execute(
            INSERT_MEMBERSHIP,
            listOf(membershipId, circleId, userId, role, createdAt, createdAt),
        )
    }

    suspend fun seedProfile(
        userId: String,
        displayName: String,
        preferredLanguage: String = "pt-PT",
    ) {
        database.execute(
            INSERT_PROFILE,
            listOf(userId, displayName, preferredLanguage, EARLY, EARLY),
        )
    }

    suspend fun seedPatient(
        patientId: String,
        displayName: String,
        circleId: String = this.circleId,
        archivedAt: String? = null,
        reminderPolicy: String = "primary_with_escalation",
    ) {
        database.execute(
            INSERT_PATIENT,
            listOf(
                patientId,
                circleId,
                displayName,
                null,
                reminderPolicy,
                null,
                DEFAULT_ESCALATION_DELAY,
                DEFAULT_MISSED_WINDOW,
                null,
                archivedAt,
                EARLY,
                EARLY,
            ),
        )
    }

    override fun close() {
        runBlocking { database.close() }
        scope.cancel()
    }

    internal companion object {
        /** Default signed-in user, used by the tests that never leave the machine. */
        const val DEFAULT_USER_ID = "11111111-1111-4111-8111-111111111111"

        /** Default current circle, used by the tests that never leave the machine. */
        const val DEFAULT_CIRCLE_ID = "22222222-2222-4222-8222-222222222222"

        /** `owner`, the role the tests' signed-in user has. */
        const val OWNER = "owner"
        const val VIEWER = "viewer"
        const val CAREGIVER = "caregiver"

        const val DEFAULT_ESCALATION_DELAY = 30
        const val DEFAULT_MISSED_WINDOW = 120

        /** The oldest membership, so the "current circle" rule picks this circle. */
        private const val FIRST_MEMBERSHIP_AT = "2026-11-18T09:00:00.000Z"
        private const val SECOND_MEMBERSHIP_AT = "2026-11-18T10:00:00.000Z"
        private const val EARLY = "2026-11-18T08:00:00.000Z"

        /**
         * A fresh directory under the module's `build/` for one test database.
         *
         * Not `@TempDir`: JUnit deletes the directory after the test, and on
         * Windows the SQLite file is still held by the native library for a
         * moment after `close()`, which fails the test on cleanup. Gradle owns
         * `build/` (and `clean` removes it), so nothing leaks.
         */
        fun testDirectory(name: String): Path = Files.createDirectories(
            Path.of("build", "tmp", "powersync-tests", "$name-${UUID.randomUUID()}"),
        )

        private const val PASSPHRASE = "integration-test-database-key"
        private const val DATABASE_FILE = "familycare-jvm-test.db"

        private const val INSERT_CIRCLE = """
            INSERT INTO care_circles (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)
        """

        private const val INSERT_MEMBERSHIP = """
            INSERT INTO memberships (id, circle_id, user_id, role, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """

        private const val INSERT_PROFILE = """
            INSERT INTO profiles (id, display_name, preferred_language, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
        """

        private const val INSERT_PATIENT = """
            INSERT INTO patients (
                id, circle_id, display_name, linked_user_id, reminder_policy,
                primary_caregiver_id, escalation_delay_minutes, missed_window_minutes,
                agreement_note, archived_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
