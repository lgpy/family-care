package io.github.lgpy.familycare.feature.appointments.ui

import io.github.lgpy.familycare.feature.appointments.data.AppointmentsError
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsResult

/**
 * Why an appointments write failed, in the app's own terms.
 *
 * [AppointmentsError] is the data layer's vocabulary; this is what a screen may
 * show. A viewer's refusal and a missing circle look the same to the person
 * ("not allowed"), while a validation failure is field-level and already shown
 * inline, so it only needs a generic hint here.
 */
enum class AppointmentsUiError {
    NotAllowed,
    NotFound,
    Invalid,
    Unknown,
}

/** The screen-level error for a failed write, or null when it succeeded. */
fun AppointmentsResult<*>.uiError(): AppointmentsUiError? = when (this) {
    is AppointmentsResult.Success -> null
    is AppointmentsResult.Failure -> error.toUiError()
}

fun AppointmentsError.toUiError(): AppointmentsUiError = when (this) {
    AppointmentsError.NoCircle, AppointmentsError.NotAllowed -> AppointmentsUiError.NotAllowed
    AppointmentsError.NotFound, AppointmentsError.AlreadyExists -> AppointmentsUiError.NotFound
    is AppointmentsError.Invalid -> AppointmentsUiError.Invalid
    AppointmentsError.Unknown -> AppointmentsUiError.Unknown
}
