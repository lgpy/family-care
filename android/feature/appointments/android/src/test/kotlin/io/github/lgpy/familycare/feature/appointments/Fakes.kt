package io.github.lgpy.familycare.feature.appointments

import io.github.lgpy.familycare.core.domain.circle.CareCircle
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.CircleSetupState
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.InvitationId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.MembershipId
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRules
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.feature.appointments.data.AppointmentEntry
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsError
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsResult
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentId
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/*
 * Fakes and fixtures for the appointments screen view-model tests.
 *
 * Every repository is a set of mutable flows plus the calls the view model made,
 * so a test drives the screen state directly and asserts what was written
 * without PowerSync, Supabase or Android.
 */

val testCircleId = CareCircleId("circle-1")
val testCircle = CareCircle(id = testCircleId, name = "Família")

val meMember = CircleMember(
    membershipId = MembershipId("m-me"),
    userId = "u1",
    displayName = "Me",
    role = MemberRole.OWNER,
    isMe = true,
)

val momMember = CircleMember(
    membershipId = MembershipId("m-mom"),
    userId = "u2",
    displayName = "Mom",
    role = MemberRole.CAREGIVER,
    isMe = false,
)

val viewerMember = CircleMember(
    membershipId = MembershipId("m-viewer"),
    userId = "u3",
    displayName = "Viewer",
    role = MemberRole.VIEWER,
    isMe = false,
)

val grandpaId = PatientId("patient-grandpa")
val grandmaId = PatientId("patient-grandma")

fun patient(
    id: PatientId = grandpaId,
    displayName: String = "Grandpa",
): Patient = Patient(id = id, circleId = testCircleId, displayName = displayName)

fun appointment(
    id: String = "appointment-1",
    patientId: PatientId = grandpaId,
    kind: AppointmentKind = AppointmentKind.CONSULTATION,
    startsLocal: LocalDateTime = LocalDateTime.of(2026, 11, 18, 15, 0),
    title: String? = "Cardiologia",
    place: String? = "Hospital",
    doctor: String? = "Dra. Silva",
    companionUserId: String? = null,
    followUpOf: AppointmentId? = null,
    cancelledAt: Instant? = null,
    timeZone: ZoneId = ZoneId.of("Europe/Lisbon"),
): Appointment = Appointment(
    id = AppointmentId(id),
    patientId = patientId,
    kind = kind,
    startsLocal = startsLocal,
    timeZone = timeZone,
    title = title,
    place = place,
    doctor = doctor,
    companionUserId = companionUserId,
    followUpOf = followUpOf,
    cancelledAt = cancelledAt,
)

fun entry(
    value: Appointment = appointment(),
    patientName: String = "Grandpa",
): AppointmentEntry = AppointmentEntry(appointment = value, patientName = patientName)

/** Fixed "now" of the tests: 2026-11-18 10:00 in Lisbon. */
val testNow: Instant = Instant.parse("2026-11-18T10:00:00Z")

/** 2026-11-18, the local date [testNow] falls on in Lisbon. */
val testToday: LocalDate = LocalDate.of(2026, 11, 18)

class FakeAppointmentRepository : AppointmentRepository {

    val agendaFlow = MutableStateFlow<List<AppointmentEntry>>(emptyList())

    private val perPatient = mutableMapOf<String, MutableStateFlow<List<Appointment>>>()
    private val observed = mutableMapOf<String, MutableStateFlow<Appointment?>>()

    /** When set, every write fails with this error instead of succeeding. */
    var writeError: AppointmentsError? = null

    val created = mutableListOf<Appointment>()
    val updated = mutableListOf<Appointment>()
    val cancelled = mutableListOf<AppointmentId>()
    val deleted = mutableListOf<AppointmentId>()

    override fun observeForPatient(patientId: PatientId): Flow<List<Appointment>> =
        perPatient.getOrPut(patientId.value) { MutableStateFlow(emptyList()) }

    override fun observeUpcoming(from: Instant, includeCancelled: Boolean): Flow<List<AppointmentEntry>> =
        agendaFlow

    override fun observeAgenda(includeCancelled: Boolean): Flow<List<AppointmentEntry>> = agendaFlow

    override fun observe(id: AppointmentId): Flow<Appointment?> =
        observed.getOrPut(id.value) { MutableStateFlow(null) }

    /** Makes [observe] emit [value] for its id. */
    fun setAppointment(value: Appointment) {
        observed.getOrPut(value.id.value) { MutableStateFlow(null) }.value = value
    }

    fun setPatientAppointments(patientId: PatientId, values: List<Appointment>) {
        perPatient.getOrPut(patientId.value) { MutableStateFlow(emptyList()) }.value = values
    }

    override suspend fun create(appointment: Appointment): AppointmentsResult<AppointmentId> {
        writeError?.let { return AppointmentsResult.Failure(it) }
        created += appointment
        setAppointment(appointment)
        return AppointmentsResult.Success(appointment.id)
    }

    override suspend fun update(appointment: Appointment): AppointmentsResult<AppointmentId> {
        writeError?.let { return AppointmentsResult.Failure(it) }
        updated += appointment
        setAppointment(appointment)
        return AppointmentsResult.Success(appointment.id)
    }

    override suspend fun cancel(id: AppointmentId): AppointmentsResult<Unit> {
        writeError?.let { return AppointmentsResult.Failure(it) }
        cancelled += id
        return AppointmentsResult.Success(Unit)
    }

    override suspend fun delete(id: AppointmentId): AppointmentsResult<Unit> {
        writeError?.let { return AppointmentsResult.Failure(it) }
        deleted += id
        return AppointmentsResult.Success(Unit)
    }
}

class FakeCareCircleRepository : CareCircleRepository {

    val roleFlow = MutableStateFlow<MemberRole?>(MemberRole.OWNER)
    override val myRole: Flow<MemberRole?> = roleFlow

    val membersFlow = MutableStateFlow(listOf(meMember, momMember))
    override val members: Flow<List<CircleMember>> = membersFlow

    override val circle: Flow<CareCircle?> = flowOf(testCircle)

    override val setupState: Flow<CircleSetupState> =
        flowOf(CircleSetupState.HasCircle(testCircle, MemberRole.OWNER))

    override suspend fun createCircle(name: String): CoreResult<CareCircleId> =
        CoreResult.Success(testCircleId)

    override suspend fun acceptInvitation(code: String): CoreResult<CareCircleId> =
        CoreResult.Success(testCircleId)

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

class FakePatientRepository : PatientRepository {

    val patientsFlow = MutableStateFlow(listOf(patient()))
    override val patients: Flow<List<Patient>> = patientsFlow

    private val observed = mutableMapOf<String, MutableStateFlow<Patient?>>()

    override fun observe(patientId: PatientId): Flow<Patient?> =
        observed.getOrPut(patientId.value) { MutableStateFlow(null) }

    fun setPatient(value: Patient) {
        observed.getOrPut(value.id.value) { MutableStateFlow(null) }.value = value
    }

    override fun newPatient(
        circleId: CareCircleId,
        displayName: String,
        linkedUserId: String?,
        birthYear: Int?,
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
        birthYear = birthYear,
        chosenPrimaryCaregiverId = chosenPrimaryCaregiverId,
        reminderPolicy = reminderPolicy,
        escalationDelayMinutes = escalationDelayMinutes,
        missedWindowMinutes = missedWindowMinutes,
        agreementNote = agreementNote,
    )

    override suspend fun save(patient: Patient): CoreResult<PatientId> =
        CoreResult.Success(patient.id)

    val archivedFlow = MutableStateFlow(listOf<Patient>())
    override val archivedPatients: Flow<List<Patient>> = archivedFlow

    override suspend fun archive(patientId: PatientId): CoreResult<PatientId> {
        val patient = observed[patientId.value]?.value ?: return CoreResult.Failure(CoreError.NotFound)
        observed.getValue(patientId.value).value =
            patient.copy(archivedAt = kotlin.time.Instant.parse("2026-11-19T10:00:00Z"))
        return CoreResult.Success(patientId)
    }

    override suspend fun unarchive(patientId: PatientId): CoreResult<PatientId> {
        val patient = observed[patientId.value]?.value ?: return CoreResult.Failure(CoreError.NotFound)
        observed.getValue(patientId.value).value = patient.copy(archivedAt = null)
        return CoreResult.Success(patientId)
    }

    override suspend fun erase(patientId: PatientId): CoreResult<PatientId> =
        CoreResult.Success(patientId)

    override suspend fun logExport(
        patientId: PatientId,
        format: io.github.lgpy.familycare.core.domain.circle.PatientExportFormat,
    ): CoreResult<Unit> = CoreResult.Success(Unit)
}
