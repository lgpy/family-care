package io.github.lgpy.familycare.core.data.push

import io.github.jan.supabase.postgrest.Postgrest
import io.github.lgpy.familycare.core.data.sync.CORE_SCHEMA
import io.github.lgpy.familycare.core.domain.push.PushDeviceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [PushDeviceRepository] over PostgREST: the two `core.*` RPCs from the M4
 * push migration (D27).
 *
 * The token is a bearer credential for one install, so nothing here logs it and
 * a failure is returned as a plain [Result.failure] — the caller retries in the
 * background and never shows the token or the server text.
 */
internal class SupabasePushDeviceRepository(
    private val postgrest: Postgrest,
) : PushDeviceRepository {

    override suspend fun register(token: String): Result<Unit> = call(REGISTER, token)

    override suspend fun unregister(token: String): Result<Unit> = call(UNREGISTER, token)

    private suspend fun call(rpc: String, token: String): Result<Unit> = try {
        postgrest.rpc(
            rpc,
            JsonObject(mapOf("p_token" to JsonPrimitive(token))),
        ) {
            schema = CORE_SCHEMA
        }
        Result.success(Unit)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }

    private companion object {
        const val REGISTER = "register_push_device"
        const val UNREGISTER = "unregister_push_device"
    }
}
