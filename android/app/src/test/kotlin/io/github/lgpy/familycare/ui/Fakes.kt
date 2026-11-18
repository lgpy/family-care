package io.github.lgpy.familycare.ui

import app.cash.turbine.TurbineTestContext
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircle
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.CircleSetupState
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.CoreUnitResult
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.InvitationId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.MembershipId
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRules
import io.github.lgpy.familycare.core.domain.circle.Profile
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fakes for the M2 screen tests: every repository is a set of mutable state
 * flows plus the last call, so a test can drive the screen state directly and
 * assert what the view model sent without Supabase, PowerSync or Android.
 */
class FakeCareCircleRepository : CareCircleRepository {

    val setupStateFlow = MutableStateFlow<CircleSetupState>(CircleSetupState.NoCircle)
    override val setupState: Flow<CircleSetupState> = setupStateFlow

    val circleFlow = MutableStateFlow<CareCircle?>(null)
    override val circle: Flow<CareCircle?> = circleFlow

    val myRoleFlow = MutableStateFlow<MemberRole?>(null)
    override val myRole: Flow<MemberRole?> = myRoleFlow

    val membersFlow = MutableStateFlow<List<CircleMember>>(emptyList())
    override val members: Flow<List<CircleMember>> = membersFlow

    var createCircleResult: CoreResult<CareCircleId> = CoreResult.Success(testCircle.id)
    var lastCreatedCircleName: String? = null

    var acceptInvitationResult: CoreResult<CareCircleId> = CoreResult.Success(testCircle.id)
    var lastAcceptedCode: String? = null

    var createInvitationResult: CoreResult<String> = CoreResult.Success(TEST_INVITE_CODE)
    var lastCreatedInvitation: Pair<String, MemberRole>? = null
    var createInvitationCalls: Int = 0

    var pendingInvitationsResult: CoreResult<List<Invitation>> = CoreResult.Success(emptyList())
    var listInvitationsCalls: Int = 0

    var revokeInvitationResult: CoreUnitResult = CoreResult.Success(Unit)
    val revokedInvitations = mutableListOf<InvitationId>()

    var removeMemberResult: CoreUnitResult = CoreResult.Success(Unit)
    val removedMemberships = mutableListOf<MembershipId>()

    /** Emits [state] and keeps the three local flows consistent with it. */
    fun setSetupState(state: CircleSetupState) {
        setupStateFlow.value = state
    }

    fun setCircle(circle: CareCircle?, role: MemberRole?, members: List<CircleMember>) {
        circleFlow.value = circle
        myRoleFlow.value = role
        membersFlow.value = members
    }

    override suspend fun createCircle(name: String): CoreResult<CareCircleId> {
        lastCreatedCircleName = name
        return createCircleResult
    }

    override suspend fun acceptInvitation(code: String): CoreResult<CareCircleId> {
        lastAcceptedCode = code
        return acceptInvitationResult
    }

    override suspend fun createInvitation(
        email: String,
        role: MemberRole,
    ): CoreResult<String> {
        createInvitationCalls += 1
        lastCreatedInvitation = email to role
        return createInvitationResult
    }

    override suspend fun listPendingInvitations(): CoreResult<List<Invitation>> {
        listInvitationsCalls += 1
        return pendingInvitationsResult
    }

    override suspend fun revokeInvitation(invitationId: InvitationId): CoreUnitResult {
        revokedInvitations += invitationId
        return revokeInvitationResult
    }

    override suspend fun removeMember(membershipId: MembershipId): CoreUnitResult {
        removedMemberships += membershipId
        return removeMemberResult
    }
}

class FakePatientRepository : PatientRepository {

    val patientsFlow = MutableStateFlow<List<Patient>>(emptyList())
    override val patients: Flow<List<Patient>> = patientsFlow

    private val observed = mutableMapOf<String, MutableStateFlow<Patient?>>()

    var saveResult: CoreResult<PatientId>? = null
    val saved = mutableListOf<Patient>()
    private var nextId = 0

    override fun observe(patientId: PatientId): Flow<Patient?> =
        observed.getOrPut(patientId.value) { MutableStateFlow(null) }

    /** Makes [observe] emit an existing patient for the edit form. */
    fun setPatient(patient: Patient) {
        observed.getOrPut(patient.id.value) { MutableStateFlow(null) }.value = patient
    }

    override fun newPatient(
        circleId: CareCircleId,
        displayName: String,
        linkedUserId: String?,
        chosenPrimaryCaregiverId: String?,
        reminderPolicy: ReminderPolicy,
        escalationDelayMinutes: Int,
        missedWindowMinutes: Int,
        agreementNote: String?,
    ): Patient {
        nextId += 1
        return PatientRules.newPatient(
            id = PatientId("new-patient-$nextId"),
            circleId = circleId,
            displayName = displayName,
            linkedUserId = linkedUserId,
            chosenPrimaryCaregiverId = chosenPrimaryCaregiverId,
            reminderPolicy = reminderPolicy,
            escalationDelayMinutes = escalationDelayMinutes,
            missedWindowMinutes = missedWindowMinutes,
            agreementNote = agreementNote,
        )
    }

    override suspend fun save(patient: Patient): CoreResult<PatientId> {
        saved += patient
        return saveResult ?: CoreResult.Success(patient.id)
    }
}

class FakeProfileRepository : ProfileRepository {

    val profileFlow = MutableStateFlow<Profile?>(null)
    override val myProfile: Flow<Profile?> = profileFlow

    var updateResult: CoreUnitResult = CoreResult.Success(Unit)
    val updates = mutableListOf<String>()

    var languageUpdateResult: CoreUnitResult = CoreResult.Success(Unit)
    val languageUpdates = mutableListOf<String>()

    override suspend fun updateDisplayName(displayName: String): CoreUnitResult {
        updates += displayName
        return updateResult
    }

    override suspend fun updatePreferredLanguage(preferredLanguage: String): CoreUnitResult {
        languageUpdates += preferredLanguage
        return languageUpdateResult
    }
}

class FakeSyncService(
    initial: SyncState = SyncState(connected = true, hasSynced = true),
) : SyncService {

    val stateFlow = MutableStateFlow(initial)
    override val state: StateFlow<SyncState> = stateFlow
    var started = false
    var acknowledgedRejections = 0

    override fun start() {
        started = true
    }

    override fun acknowledgeRejectedUpload() {
        acknowledgedRejections += 1
        stateFlow.value = stateFlow.value.copy(rejectedUpload = null)
    }
}

class FakeAuthRepository : AuthRepository {

    private val _state = MutableStateFlow<AuthState>(
        AuthState.SignedIn(userId = "u1", email = "me@example.com"),
    )
    override val state: StateFlow<AuthState> = _state

    var signOutCount = 0

    override suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun signOut(): Result<Unit> {
        signOutCount += 1
        _state.value = AuthState.SignedOut
        return Result.success(Unit)
    }
}

/** A code of 32 Crockford base32 characters (no I, L, O or U). */
const val TEST_INVITE_CODE: String = "ABCD2345EFGH6789JKMN2345PQRS6789"

val testCircle = CareCircle(id = CareCircleId("circle-1"), name = "Família")

val ownerMember = CircleMember(
    membershipId = MembershipId("membership-owner"),
    userId = "u1",
    displayName = "Me",
    role = MemberRole.OWNER,
    isMe = true,
)

val caregiverMember = CircleMember(
    membershipId = MembershipId("membership-caregiver"),
    userId = "u2",
    displayName = "Ana",
    role = MemberRole.CAREGIVER,
    isMe = false,
)

/** Awaits emissions until [predicate] holds, skipping intermediate states. */
internal suspend fun <T> TurbineTestContext<T>.awaitWhere(predicate: (T) -> Boolean): T {
    var item = awaitItem()
    while (!predicate(item)) {
        item = awaitItem()
    }
    return item
}
