package io.github.lgpy.familycare.core.data.integration

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.lgpy.familycare.core.data.circle.SupabaseCareCircleRpc
import io.github.lgpy.familycare.core.data.sync.CORE_SCHEMA
import io.github.lgpy.familycare.core.data.sync.PATIENTS_TABLE
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions

/**
 * The local Supabase stack, for the upload tests.
 *
 * Nothing is hardcoded: the URL and the publishable (anon) key are read from
 * `FAMILYCARE_SUPABASE_URL` / `FAMILYCARE_SUPABASE_KEY`, which is what
 * `supabase status -o env` prints (CI exports the same two values). Without
 * them — or when the API does not answer — [open] aborts the test with an
 * assumption, so `./gradlew build` stays green offline.
 *
 * Users are created through the real auth API with random emails, because the
 * policies being tested need a real JWT (`auth.uid()`); no service key is used
 * and nothing is written into `auth.users` behind GoTrue's back.
 */
internal class LocalStack private constructor(
    private val url: String,
    private val key: String,
) : AutoCloseable {

    /** One signed-in test account and the client that holds its session. */
    internal class TestUser(
        val client: SupabaseClient,
        val userId: String,
        val email: String,
    )

    private val clients = mutableListOf<SupabaseClient>()

    /**
     * A client with an in-memory session, so a test run never writes a session
     * to the developer's home directory.
     */
    private fun newClient(): SupabaseClient = createSupabaseClient(url, key) {
        install(Auth) {
            sessionManager = MemorySessionManager()
        }
        install(Postgrest)
    }.also(clients::add)

    /** Signs up a fresh account (email confirmations are off in the local config). */
    suspend fun newUser(): TestUser {
        val email = "jvm-${UUID.randomUUID()}@example.test"
        val client = newClient()

        client.auth.signUpWith(Email) {
            this.email = email
            password = PASSWORD
        }
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: signIn(client, email)
            ?: error("The local stack did not return a session for a new test user")

        return TestUser(client, userId, email)
    }

    private suspend fun signIn(client: SupabaseClient, email: String): String? {
        client.auth.signInWith(Email) {
            this.email = email
            password = PASSWORD
        }
        return client.auth.currentSessionOrNull()?.user?.id
    }

    /** `core.create_circle` through the app's own RPC client. */
    suspend fun createCircle(owner: TestUser, name: String = "JVM family"): String =
        SupabaseCareCircleRpc(owner.client.postgrest).createCircle(name)

    /**
     * Adds a second account to [circleId] as a viewer, through the real
     * invitation flow: the owner issues a code, the invitee accepts it. The
     * viewer role can read the circle but not write patients.
     */
    suspend fun addViewer(owner: TestUser, circleId: String): TestUser {
        val viewer = newUser()
        val code = SupabaseCareCircleRpc(owner.client.postgrest)
            .createInvitation(circleId, viewer.email, MemberRole.VIEWER)
        SupabaseCareCircleRpc(viewer.client.postgrest).acceptInvitation(code)
        return viewer
    }

    /** The patient's display name as PostgREST returns it to [user], or null. */
    suspend fun patientName(user: TestUser, patientId: String): String? {
        val result = user.client.postgrest.from(CORE_SCHEMA, PATIENTS_TABLE)
            .select(Columns.list("display_name")) {
                filter { eq("id", patientId) }
            }
        val rows = Json.parseToJsonElement(result.data) as? JsonArray ?: return null
        return (rows.firstOrNull() as? JsonObject)?.get("display_name")?.jsonPrimitive?.content
    }

    override fun close() {
        runBlocking { clients.forEach { client -> client.close() } }
    }

    internal companion object {
        const val URL_ENV = "FAMILYCARE_SUPABASE_URL"
        const val KEY_ENV = "FAMILYCARE_SUPABASE_KEY"

        private const val PASSWORD = "jvm-integration-test-password"

        /**
         * The local stack, or a skipped test.
         *
         * The env vars are the switch; the health check only turns "set but the
         * stack was stopped" into a skip instead of a connection timeout.
         */
        fun open(): LocalStack {
            val url = System.getenv(URL_ENV)?.trim().orEmpty()
            val key = System.getenv(KEY_ENV)?.trim().orEmpty()

            Assumptions.assumeTrue(
                url.isNotEmpty() && key.isNotEmpty(),
                "Set $URL_ENV and $KEY_ENV (from `supabase status -o env`) to run the local upload tests",
            )
            Assumptions.assumeTrue(
                isReachable(url),
                "No local Supabase API at $url; start it with `npx supabase start` in backend/",
            )

            return LocalStack(url.trimEnd('/'), key)
        }

        private fun isReachable(url: String): Boolean = try {
            val connection = URI("$url/auth/v1/health").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = HEALTH_TIMEOUT_MS
            connection.readTimeout = HEALTH_TIMEOUT_MS
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }

        private const val HEALTH_TIMEOUT_MS = 2_000
    }
}
