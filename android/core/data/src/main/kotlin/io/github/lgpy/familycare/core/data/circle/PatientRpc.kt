package io.github.lgpy.familycare.core.data.circle

import io.github.jan.supabase.postgrest.Postgrest
import io.github.lgpy.familycare.core.data.sync.CORE_SCHEMA
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The online calls the patient lifecycle needs (D30). Archive and unarchive are
 * ordinary local writes to `core.patients`, so erasing is the only RPC.
 *
 * An interface like [CareCircleRpc]: the repository owns the local database and
 * can be tested with a fake, and the mapping of the RPC's failures is testable
 * without a network.
 */
internal interface PatientRpc {

    /** `core.erase_patient(p_patient_id)`: deletes the patient and everything of theirs. */
    suspend fun erasePatient(patientId: String)

    /** `core.log_patient_export(p_patient_id, p_format)`: the audit row before a file is written. */
    suspend fun logPatientExport(patientId: String, format: String)
}

/** [PatientRpc] over PostgREST with supabase-kt, schema `core`. */
internal class SupabasePatientRpc(
    private val postgrest: Postgrest,
) : PatientRpc {

    override suspend fun erasePatient(patientId: String) {
        postgrest.rpc(
            ERASE_PATIENT,
            JsonObject(mapOf("p_patient_id" to JsonPrimitive(patientId))),
        ) {
            schema = CORE_SCHEMA
        }
    }

    override suspend fun logPatientExport(patientId: String, format: String) {
        postgrest.rpc(
            LOG_PATIENT_EXPORT,
            JsonObject(
                mapOf(
                    "p_patient_id" to JsonPrimitive(patientId),
                    "p_format" to JsonPrimitive(format),
                ),
            ),
        ) {
            schema = CORE_SCHEMA
        }
    }

    private companion object {
        const val ERASE_PATIENT = "erase_patient"
        const val LOG_PATIENT_EXPORT = "log_patient_export"
    }
}
