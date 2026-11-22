package io.github.lgpy.familycare.feature.patientinfo.data

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoField
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoValidation
import kotlinx.coroutines.flow.Flow

/**
 * What a patient-info repository call returns.
 *
 * Mirrors `core`'s `CoreResult` and the medications module's `MedsResult`, but
 * the invalid inputs are [PatientInfoField]s: the core cannot name a
 * patient-info field, and the module owns its own form (D5). Failures are
 * values, not exceptions, and never carry server text, SQL or row data
 * (security-and-privacy §4).
 */
sealed interface PatientInfoResult<out T> {

    /** The call succeeded; [value] is its result. */
    data class Success<T>(val value: T) : PatientInfoResult<T>

    /** The call failed; [error] says why in the app's own terms. */
    data class Failure(val error: PatientInfoError) : PatientInfoResult<Nothing>
}

/** Why a patient-info repository call failed. */
sealed interface PatientInfoError {

    /** The user belongs to no circle, so there is nothing to act on. */
    data object NoCircle : PatientInfoError

    /**
     * The signed-in role may not write (a viewer), the patient belongs to
     * another circle, or the user is not signed in. Nothing was written.
     */
    data object NotAllowed : PatientInfoError

    /**
     * A domain rule rejected the input before anything was written.
     * [validation] tells the form which inputs to mark; see
     * [PatientInfoField].
     */
    data class Invalid(val validation: PatientInfoValidation) : PatientInfoError

    /** Anything else (a local database error). The UI only offers "try again". */
    data object Unknown : PatientInfoError
}

/**
 * The patient-info repository: one row per patient, edited in place, last write
 * wins (D29).
 */
interface PatientInfoRepository {

    /**
     * The patient's info, or null when the patient has none yet (the row is
     * created on the first save) — or when the patient is not in a circle the
     * signed-in user belongs to.
     */
    fun observe(patientId: PatientId): Flow<PatientInfo?>

    /**
     * Stores [info] for [patientId]: an insert when the patient has no row yet,
     * an update otherwise, in one queued transaction.
     *
     * Refused (and nothing queued) for a viewer, for a patient outside the
     * current circle, and for input the domain rules reject.
     */
    suspend fun save(patientId: PatientId, info: PatientInfo): PatientInfoResult<Unit>

    /**
     * Stores what the user changed in a form that was filled from [loaded] and
     * edited into [edited]; [loaded] is null when the form started empty (the
     * patient had no row yet).
     *
     * Unlike [save] this never replaces the row with the form: only the columns
     * the user touched are written, and a list column (allergies, conditions,
     * emergency contacts) receives the user's *difference* — entries added and
     * entries removed — applied to the row's value at write time, so what
     * someone else added since the form was opened is kept. A scalar the user
     * changed takes the user's value. With no row at write time it inserts
     * [edited] whole, like [save].
     *
     * The default replaces the row, for implementations that have no stored row
     * to merge into (test fakes).
     */
    suspend fun saveChanges(
        patientId: PatientId,
        loaded: PatientInfo?,
        edited: PatientInfo,
    ): PatientInfoResult<Unit> = save(patientId, edited)
}
