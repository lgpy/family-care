package io.github.lgpy.familycare.feature.patientinfo

import io.github.lgpy.familycare.core.domain.circle.CareCircle
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.CircleSetupState
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.InvitationId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.MembershipId
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRules
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoResult
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContact
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/*
 * Fakes and fixtures for the patient-info view-model tests.
 *
 * The repository is a mutable flow plus the calls the view model made, so a test
 * drives the screen state directly and asserts what was written without
 * PowerSync, Supabase or Android.
 */

val testPatientId = PatientId("patient-grandma")

val testCircle = CareCircle(id = CareCircleId("circle-1"), name = "Família")

fun patientInfo(
    allergies: List<String> = emptyList(),
    conditions: List<String> = emptyList(),
    snsNumber: String? = null,
    familyDoctor: String? = null,
    healthCentre: String? = null,
    healthCentrePhone: String? = null,
    emergencyContacts: List<EmergencyContact> = emptyList(),
    notes: String? = null,
): PatientInfo = PatientInfo(
    allergies = allergies,
    conditions = conditions,
    snsNumber = snsNumber,
    familyDoctor = familyDoctor,
    healthCentre = healthCentre,
    healthCentrePhone = healthCentrePhone,
    emergencyContacts = emergencyContacts,
    notes = notes,
)

/** One recorded [PatientInfoRepository.save] call. */
data class SaveCall(val patientId: PatientId, val info: PatientInfo)

class FakePatientInfoRepository : PatientInfoRepository {

    val flows = mutableMapOf<String, MutableStateFlow<PatientInfo?>>()

    val saves = mutableListOf<SaveCall>()
    var saveResult: PatientInfoResult<Unit> = PatientInfoResult.Success(Unit)

    override fun observe(patientId: PatientId): Flow<PatientInfo?> =
        flows.getOrPut(patientId.value) { MutableStateFlow(null) }

    /** Sets what the patient has recorded; null is "no row yet". */
    fun setInfo(patientId: PatientId = testPatientId, info: PatientInfo?) {
        flows.getOrPut(patientId.value) { MutableStateFlow(null) }.value = info
    }

    override suspend fun save(patientId: PatientId, info: PatientInfo): PatientInfoResult<Unit> {
        saves += SaveCall(patientId, info)
        return saveResult
    }
}

/**
 * A patient of the test circle, for the read-only check: the view models ask it
 * whether the patient is archived.
 */
fun patient(archivedAt: kotlin.time.Instant? = null): Patient = Patient(
    id = testPatientId,
    circleId = testCircle.id,
    displayName = "Grandma",
    primaryCaregiverId = "u1",
    archivedAt = archivedAt,
)

class FakePatientRepository : PatientRepository {

    val observed = MutableStateFlow<Patient?>(patient())
    override val patients: Flow<List<Patient>> = observed.map { listOfNotNull(it) }
    override val archivedPatients: Flow<List<Patient>> = flowOf(emptyList())

    override fun observe(patientId: PatientId): Flow<Patient?> = observed

    override fun newPatient(
        circleId: CareCircleId,
        displayName: String,
        linkedUserId: String?,
        chosenPrimaryCaregiverId: String?,
        reminderPolicy: ReminderPolicy,
        escalationDelayMinutes: Int,
        missedWindowMinutes: Int,
        agreementNote: String?,
    ): Patient = PatientRules.newPatient(
        id = PatientId("patient-new"),
        circleId = circleId,
        displayName = displayName,
        linkedUserId = linkedUserId,
        chosenPrimaryCaregiverId = chosenPrimaryCaregiverId,
        reminderPolicy = reminderPolicy,
        escalationDelayMinutes = escalationDelayMinutes,
        missedWindowMinutes = missedWindowMinutes,
        agreementNote = agreementNote,
    )

    override suspend fun save(patient: Patient): CoreResult<PatientId> =
        CoreResult.Success(patient.id)

    override suspend fun archive(patientId: PatientId): CoreResult<PatientId> =
        CoreResult.Success(patientId)

    override suspend fun unarchive(patientId: PatientId): CoreResult<PatientId> =
        CoreResult.Success(patientId)

    override suspend fun erase(patientId: PatientId): CoreResult<PatientId> =
        CoreResult.Success(patientId)
}

class FakeCareCircleRepository : CareCircleRepository {
    val circleFlow = MutableStateFlow<CareCircle?>(testCircle)
    override val circle: Flow<CareCircle?> = circleFlow

    val roleFlow = MutableStateFlow<MemberRole?>(MemberRole.OWNER)
    override val myRole: Flow<MemberRole?> = roleFlow

    val membersFlow = MutableStateFlow(
        listOf(
            CircleMember(
                membershipId = MembershipId("m-me"),
                userId = "u1",
                displayName = "Me",
                role = MemberRole.OWNER,
                isMe = true,
            ),
        ),
    )
    override val members: Flow<List<CircleMember>> = membersFlow

    override val setupState: Flow<CircleSetupState> = flowOf(
        CircleSetupState.HasCircle(testCircle, MemberRole.OWNER),
    )

    override suspend fun createCircle(name: String): CoreResult<CareCircleId> =
        CoreResult.Success(testCircle.id)

    override suspend fun acceptInvitation(code: String): CoreResult<CareCircleId> =
        CoreResult.Success(testCircle.id)

    override suspend fun createInvitation(
        email: String,
        role: MemberRole,
    ): CoreResult<String> = CoreResult.Success("code")

    override suspend fun listPendingInvitations(): CoreResult<List<Invitation>> =
        CoreResult.Success(emptyList())

    override suspend fun revokeInvitation(invitationId: InvitationId): CoreResult<Unit> =
        CoreResult.Success(Unit)

    override suspend fun removeMember(membershipId: MembershipId): CoreResult<Unit> =
        CoreResult.Success(Unit)
}
