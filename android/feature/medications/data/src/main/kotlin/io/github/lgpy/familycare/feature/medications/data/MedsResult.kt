package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.feature.medications.domain.MedicationField

/**
 * What a medications repository call returns.
 *
 * Mirrors `core`'s `CoreResult`/`CoreError`, but the invalid fields are
 * [MedicationField]s: the core cannot name a medications field, and the
 * medications feature owns its own form fields (D5). A screen maps each field to
 * its label, exactly as it already does for the core's `CoreField`.
 *
 * Failures are values, not exceptions, and never carry server text, SQL or row
 * data (security-and-privacy §4).
 */
sealed interface MedsResult<out T> {

    /** The call succeeded; [value] is its result. */
    data class Success<T>(val value: T) : MedsResult<T>

    /** The call failed; [error] says why in the app's own terms. */
    data class Failure(val error: MedsError) : MedsResult<Nothing>
}

/** Why a medications repository call failed. */
sealed interface MedsError {

    /** The user belongs to no circle, so there is nothing to act on. */
    data object NoCircle : MedsError

    /**
     * The signed-in role may not write (a viewer), the row belongs to another
     * circle, or the user is not signed in. Nothing was written.
     */
    data object NotAllowed : MedsError

    /** The row the call addressed does not exist locally (anymore). */
    data object NotFound : MedsError

    /** The row already exists, so a create would overwrite it. */
    data object AlreadyExists : MedsError

    /**
     * A local domain rule rejected the input before anything was written.
     * [fields] tells the form which inputs to mark; see [MedicationField].
     */
    data class Invalid(val fields: Set<MedicationField>) : MedsError

    /** Anything else (a local database error). The UI only offers "try again". */
    data object Unknown : MedsError
}
