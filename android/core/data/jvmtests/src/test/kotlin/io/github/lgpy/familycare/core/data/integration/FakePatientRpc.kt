package io.github.lgpy.familycare.core.data.integration

import io.github.lgpy.familycare.core.data.circle.PatientRpc

/**
 * A [PatientRpc] that records the ids it was asked to erase and can be told to
 * fail, so the repository's success and error mapping are testable without a
 * server.
 */
internal class FakePatientRpc(
    private val failure: Throwable? = null,
) : PatientRpc {

    val erased: MutableList<String> = mutableListOf()

    override suspend fun erasePatient(patientId: String) {
        failure?.let { throw it }
        erased += patientId
    }

    val exports: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun logPatientExport(patientId: String, format: String) {
        failure?.let { throw it }
        exports += patientId to format
    }
}

/** A [PatientRpc] for tests that never erase. */
internal object UnusedPatientRpc : PatientRpc {
    override suspend fun erasePatient(patientId: String): Unit =
        throw UnsupportedOperationException("read-only test")

    override suspend fun logPatientExport(patientId: String, format: String): Unit =
        throw UnsupportedOperationException("read-only test")
}
