package io.github.lgpy.familycare.feature.appointments.data

import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField

/**
 * What an appointments repository call returns.
 *
 * Mirrors the medications module's `MedsResult`: failures are values, never
 * exceptions, and the invalid fields are the feature's own
 * [AppointmentField]s. No server text, SQL or row data is ever carried
 * (security-and-privacy §4).
 */
sealed interface AppointmentsResult<out T> {

    /** The call succeeded; [value] is its result. */
    data class Success<T>(val value: T) : AppointmentsResult<T>

    /** The call failed; [error] says why in the app's own terms. */
    data class Failure(val error: AppointmentsError) : AppointmentsResult<Nothing>
}

/** Why an appointments repository call failed. */
sealed interface AppointmentsError {

    /** The user belongs to no circle, so there is nothing to act on. */
    data object NoCircle : AppointmentsError

    /**
     * The signed-in role may not write (a viewer), the row belongs to another
     * circle, or the user is not signed in. Nothing was written.
     */
    data object NotAllowed : AppointmentsError

    /** The row the call addressed does not exist locally (anymore). */
    data object NotFound : AppointmentsError

    /** The row already exists, so a create would overwrite it. */
    data object AlreadyExists : AppointmentsError

    /**
     * A domain rule rejected the input before anything was written. [fields]
     * tells the form which inputs to mark.
     */
    data class Invalid(val fields: Set<AppointmentField>) : AppointmentsError

    /** Anything else (a local database error). The UI only offers "try again". */
    data object Unknown : AppointmentsError
}
