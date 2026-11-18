package io.github.lgpy.familycare.feature.medications.ui

import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult

/**
 * Why a medications write failed, in the app's own terms.
 *
 * [MedsError] is the data layer's vocabulary; this is what a screen may show. A
 * viewer's refusal and a missing circle look the same to the person ("not
 * allowed"), while a validation failure is field-level and already shown inline,
 * so it only needs a generic hint here.
 */
enum class MedsUiError {
    NotAllowed,
    NotFound,
    Invalid,
    Unknown,
}

/** The screen-level error for a failed write, or null when it succeeded. */
fun MedsResult<*>.uiError(): MedsUiError? = when (this) {
    is MedsResult.Success -> null
    is MedsResult.Failure -> error.toUiError()
}

fun MedsError.toUiError(): MedsUiError = when (this) {
    MedsError.NoCircle, MedsError.NotAllowed -> MedsUiError.NotAllowed
    MedsError.NotFound, MedsError.AlreadyExists -> MedsUiError.NotFound
    is MedsError.Invalid -> MedsUiError.Invalid
    MedsError.Unknown -> MedsUiError.Unknown
}

/** The order every member picker uses: me first, then by name. */
val MemberOrder: Comparator<CircleMember> =
    compareBy({ it.isMe.not() }, { it.displayName?.lowercase() ?: "" })
