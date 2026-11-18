package io.github.lgpy.familycare.core.domain.circle

/**
 * What the app knows about the signed-in user's circle.
 *
 * The three cases exist because the app must not treat "no rows yet" and "not
 * signed up for a family" the same: before the first sync finishes, the local
 * tables are empty and a naive screen would show "create a circle" to somebody
 * who already has one. [CareCircleRepository.setupState] emits this and updates
 * by itself when the circle and membership rows arrive through PowerSync.
 */
sealed interface CircleSetupState {

    /**
     * The first sync has not completed yet (PowerSync's `hasSynced` is not
     * true), or nobody is signed in. Show a loading state, not the empty state.
     */
    data object NotSyncedYet : CircleSetupState

    /**
     * The local data is current and the user belongs to no circle. Their profile
     * exists, but there is nothing to see: offer "create a circle" or "accept an
     * invitation".
     */
    data object NoCircle : CircleSetupState

    /**
     * The user belongs to [circle] with [role]. Read the patients through
     * [PatientRepository.patients] and the family list through
     * [CareCircleRepository.members].
     */
    data class HasCircle(
        val circle: CareCircle,
        val role: MemberRole,
    ) : CircleSetupState
}
