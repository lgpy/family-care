package io.github.lgpy.familycare.feature.medications

import app.cash.turbine.TurbineTestContext
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
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import io.github.lgpy.familycare.feature.medications.data.PatientDoseSettings
import io.github.lgpy.familycare.feature.medications.data.StockOverview
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.data.TodayDoseGroup
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntryId
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseSlot
import io.github.lgpy.familycare.feature.medications.domain.DoseSlotId
import io.github.lgpy.familycare.feature.medications.domain.DoseStatus
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.domain.ShoppingStatus
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.domain.StockMovement
import io.github.lgpy.familycare.feature.medications.domain.StockMovementId
import io.github.lgpy.familycare.feature.medications.domain.StockSummary
import io.github.lgpy.familycare.feature.medications.domain.TimeSlotGroup
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/*
 * Fakes and fixtures for the medications screen view-model tests.
 *
 * Every repository is a set of mutable flows plus the calls the view model made,
 * so a test drives the screen state directly and asserts what was written
 * without PowerSync, Supabase or Android. The fixtures build the domain objects
 * the data layer would emit.
 */

val testPatientId = PatientId("patient-grandpa")
val testCircle = CareCircle(id = CareCircleId("circle-1"), name = "Família")

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

fun patient(displayName: String = "Grandpa", id: PatientId = testPatientId): Patient =
    Patient(id = id, circleId = testCircle.id, displayName = displayName)

fun patientSettings(
    displayName: String = "Grandpa",
    id: PatientId = testPatientId,
): PatientDoseSettings = PatientDoseSettings(
    patientId = id,
    displayName = displayName,
    escalationDelayMinutes = 30,
    missedWindowMinutes = 120,
)

fun medication(
    id: String = "med-1",
    patientId: PatientId = testPatientId,
    name: String = "Metformin",
    strength: String? = "500 mg",
    stoppedAt: Instant? = null,
): Medication = Medication(
    id = MedicationId(id),
    patientId = patientId,
    name = name,
    strength = strength,
    form = MedicationForm.TABLET,
    stockUnit = "tablet",
    stoppedAt = stoppedAt,
)

fun regimen(
    id: String = "reg-1",
    medicationId: String = "med-1",
    startDate: LocalDate = LocalDate.of(2026, 1, 1),
    endDate: LocalDate? = null,
    times: List<DoseTime> = listOf(DoseTime(LocalTime.of(8, 0), BigDecimal.ONE)),
    scheduleType: ScheduleType = ScheduleType.DAILY,
): Regimen = Regimen(
    id = RegimenId(id),
    medicationId = MedicationId(medicationId),
    scheduleType = scheduleType,
    startDate = startDate,
    timeZone = ZoneId.of("Europe/Lisbon"),
    times = times,
    endDate = endDate,
)

fun withRegimens(
    med: Medication = medication(),
    regimens: List<Regimen> = listOf(regimen(medicationId = med.id.value)),
    patientName: String = "Grandpa",
): MedicationWithRegimens = MedicationWithRegimens(
    medication = med,
    regimens = regimens,
    patient = patientSettings(displayName = patientName),
)

fun slot(
    reg: Regimen = regimen(),
    scheduledLocal: LocalDateTime = LocalDateTime.of(2026, 11, 16, 8, 0),
    quantity: BigDecimal = BigDecimal.ONE,
): DoseSlot = DoseSlot(
    regimenId = reg.id,
    medicationId = reg.medicationId,
    scheduledLocal = scheduledLocal,
    instant = scheduledLocal.atZone(reg.timeZone).toInstant(),
    quantity = quantity,
)

fun status(
    doseSlot: DoseSlot,
    state: DoseState = DoseState.DUE,
    entries: List<DoseLogEntry> = emptyList(),
): DoseStatus = DoseStatus(slot = doseSlot, state = state, effectiveEntries = entries)

fun todayGroup(
    slots: List<DoseSlot>,
    statuses: List<DoseStatus> = slots.map { status(it) },
    patientId: PatientId = testPatientId,
    scheduledLocal: LocalDateTime = slots.first().scheduledLocal,
): TodayDoseGroup = TodayDoseGroup(
    group = TimeSlotGroup(patientId = patientId, scheduledLocal = scheduledLocal, slots = slots),
    statuses = statuses,
)

fun entry(
    slot: DoseSlot,
    id: String = "entry-1",
    outcome: DoseOutcome = DoseOutcome.TAKEN,
    actualAt: Instant = slot.instant.plusSeconds(240),
    givenByUserId: String? = "u1",
    givenByOther: Boolean = false,
    givenByName: String? = null,
    skipReason: SkipReason? = null,
    corrects: DoseLogEntryId? = null,
): DoseLogEntry = DoseLogEntry(
    id = DoseLogEntryId(id),
    regimenId = slot.regimenId,
    medicationId = slot.medicationId,
    scheduledLocal = slot.scheduledLocal,
    outcome = outcome,
    actualAt = actualAt,
    confirmedBy = "u1",
    skipReason = skipReason,
    givenByUserId = if (givenByOther) null else givenByUserId,
    givenByOther = givenByOther,
    givenByName = givenByName,
    correctsEntryId = corrects,
)

/** One recorded [DoseLogRepository.confirm] call. */
data class ConfirmCall(
    val group: TimeSlotGroup,
    val skipped: Map<DoseSlotId, SkipReason>,
    val givenBy: GivenBy,
    val note: String?,
)

/** One recorded [DoseLogRepository.correct] call. */
data class CorrectCall(
    val entry: DoseLogEntry,
    val outcome: DoseOutcome,
    val skipReason: SkipReason?,
    val givenBy: GivenBy,
    val note: String?,
)

/** Awaits emissions until [predicate] holds, skipping intermediate states. */
internal suspend fun <T> TurbineTestContext<T>.awaitWhere(predicate: (T) -> Boolean): T {
    var item = awaitItem()
    while (!predicate(item)) {
        item = awaitItem()
    }
    return item
}

class FakeMedicationRepository : MedicationRepository {

    val patientFlows = mutableMapOf<String, MutableStateFlow<List<MedicationWithRegimens>>>()
    val observed = mutableMapOf<String, MutableStateFlow<MedicationWithRegimens?>>()
    val activeFlow = MutableStateFlow<List<MedicationWithRegimens>>(emptyList())

    override val activeMedications: Flow<List<MedicationWithRegimens>> = activeFlow

    val created = mutableListOf<CreateCall>()
    var createResult: MedsResult<MedicationId>? = null
    val updated = mutableListOf<Medication>()
    var updateResult: MedsResult<MedicationId>? = null

    val changes = mutableListOf<RegimenChangeCall>()
    var changeResult: MedsResult<RegimenId>? = null

    val stops = mutableListOf<StopCall>()
    var stopResult: MedsResult<Unit> = MedsResult.Success(Unit)

    override fun observe(patientId: PatientId): Flow<List<MedicationWithRegimens>> =
        patientFlows.getOrPut(patientId.value) { MutableStateFlow(emptyList()) }

    override fun observeMedication(medicationId: MedicationId): Flow<MedicationWithRegimens?> =
        observed.getOrPut(medicationId.value) { MutableStateFlow(null) }

    fun setMedication(item: MedicationWithRegimens) {
        observed.getOrPut(item.id.value) { MutableStateFlow(null) }.value = item
        activeFlow.value = activeFlow.value.filterNot { it.id == item.id } + item
    }

    fun setPatientMedications(patientId: PatientId, items: List<MedicationWithRegimens>) {
        patientFlows.getOrPut(patientId.value) { MutableStateFlow(emptyList()) }.value = items
    }

    override suspend fun create(
        medication: Medication,
        firstRegimen: Regimen,
        openingQuantity: BigDecimal?,
    ): MedsResult<MedicationId> {
        created += CreateCall(medication, firstRegimen, openingQuantity)
        return createResult ?: MedsResult.Success(medication.id)
    }

    override suspend fun update(medication: Medication): MedsResult<MedicationId> {
        updated += medication
        return updateResult ?: MedsResult.Success(medication.id)
    }

    override suspend fun changeRegimen(
        current: Regimen,
        effectiveFrom: LocalDate,
        times: List<DoseTime>,
        scheduleType: ScheduleType,
        weekdays: Int?,
        intervalDays: Int?,
        timeZone: ZoneId,
    ): MedsResult<RegimenId> {
        changes += RegimenChangeCall(current, effectiveFrom, times, scheduleType, weekdays, intervalDays)
        return changeResult ?: MedsResult.Success(RegimenId("reg-new"))
    }

    override suspend fun stop(
        medication: Medication,
        regimen: Regimen,
        lastDay: LocalDate,
    ): MedsResult<Unit> {
        stops += StopCall(medication, regimen, lastDay)
        return stopResult
    }
}

data class RegimenChangeCall(
    val current: Regimen,
    val effectiveFrom: LocalDate,
    val times: List<DoseTime>,
    val scheduleType: ScheduleType,
    val weekdays: Int?,
    val intervalDays: Int?,
)

data class StopCall(val medication: Medication, val regimen: Regimen, val lastDay: LocalDate)

/** One recorded [MedicationRepository.create] call, with its opening count (D28). */
data class CreateCall(
    val medication: Medication,
    val regimen: Regimen,
    val openingQuantity: BigDecimal?,
)

class FakeDoseLogRepository : DoseLogRepository {

    val todayFlow = MutableStateFlow<List<TodayDoseGroup>>(emptyList())
    val entryFlows = mutableMapOf<String, MutableStateFlow<List<DoseLogEntry>>>()

    override val dosesToday: Flow<List<TodayDoseGroup>> = todayFlow

    val confirmations = mutableListOf<ConfirmCall>()
    var confirmResult: MedsResult<Unit> = MedsResult.Success(Unit)

    val corrections = mutableListOf<CorrectCall>()
    var correctResult: MedsResult<DoseLogEntryId> = MedsResult.Success(DoseLogEntryId("entry-new"))

    override fun observeEntries(
        regimenIds: Collection<RegimenId>,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<DoseLogEntry>> = flowOf(emptyList())

    override fun observeMedicationEntries(medicationId: MedicationId): Flow<List<DoseLogEntry>> =
        entryFlows.getOrPut(medicationId.value) { MutableStateFlow(emptyList()) }

    fun setMedicationEntries(medicationId: MedicationId, entries: List<DoseLogEntry>) {
        entryFlows.getOrPut(medicationId.value) { MutableStateFlow(emptyList()) }.value = entries
    }

    override suspend fun confirm(
        group: TimeSlotGroup,
        skipped: Map<DoseSlotId, SkipReason>,
        givenBy: GivenBy,
        note: String?,
    ): MedsResult<Unit> {
        confirmations += ConfirmCall(group, skipped, givenBy, note)
        return confirmResult
    }

    override suspend fun correct(
        entry: DoseLogEntry,
        outcome: DoseOutcome,
        skipReason: SkipReason?,
        actualQuantity: BigDecimal?,
        givenBy: GivenBy,
        note: String?,
    ): MedsResult<DoseLogEntryId> {
        corrections += CorrectCall(entry, outcome, skipReason, givenBy, note)
        return correctResult
    }
}

/** A stock summary with the numbers a screen test wants to see (D28). */
fun stockSummary(
    stock: String = "30",
    dailyConsumption: String = "1",
    daysRemaining: Int? = 30,
    low: Boolean = false,
    shoppingStatus: ShoppingStatus? = null,
): StockSummary = StockSummary(
    stock = BigDecimal(stock),
    dailyConsumption = BigDecimal(dailyConsumption),
    daysRemaining = daysRemaining,
    low = low,
    shoppingStatus = shoppingStatus,
)

fun stockOverview(
    item: MedicationWithRegimens = withRegimens(),
    summary: StockSummary = stockSummary(),
    movements: List<StockMovement> = emptyList(),
): StockOverview = StockOverview(item = item, movements = movements, summary = summary)

/** One recorded [StockRepository.recordCount] call. */
data class CountCall(
    val medicationId: MedicationId,
    val quantity: BigDecimal,
    val note: String?,
)

/** One recorded [StockRepository.recordRefill] call. */
data class RefillCall(val medicationId: MedicationId, val quantity: BigDecimal)

/** One recorded [StockRepository.recordDiscard] call. */
data class DiscardCall(
    val medicationId: MedicationId,
    val quantity: BigDecimal,
    val reason: String,
)

class FakeStockRepository : StockRepository {

    val overviewFlows = mutableMapOf<String, MutableStateFlow<StockOverview?>>()
    val movementFlows = mutableMapOf<String, MutableStateFlow<List<StockMovement>>>()
    val overviewsFlow = MutableStateFlow<List<StockOverview>>(emptyList())

    override val overviews: Flow<List<StockOverview>> = overviewsFlow

    val counts = mutableListOf<CountCall>()
    var countResult: MedsResult<StockMovementId> = MedsResult.Success(StockMovementId("stock-count"))

    val refills = mutableListOf<RefillCall>()
    var refillResult: MedsResult<StockMovementId> = MedsResult.Success(StockMovementId("stock-refill"))

    val discards = mutableListOf<DiscardCall>()
    var discardResult: MedsResult<StockMovementId> = MedsResult.Success(StockMovementId("stock-discard"))

    override fun observeOverview(medicationId: MedicationId): Flow<StockOverview?> =
        overviewFlows.getOrPut(medicationId.value) { MutableStateFlow(null) }

    fun setOverview(overview: StockOverview) {
        overviewFlows.getOrPut(overview.medication.id.value) { MutableStateFlow(null) }.value = overview
        overviewsFlow.value = overviewsFlow.value.filterNot { it.medication.id == overview.medication.id } +
            overview
    }

    override fun observeMovements(medicationId: MedicationId): Flow<List<StockMovement>> =
        movementFlows.getOrPut(medicationId.value) { MutableStateFlow(emptyList()) }

    override suspend fun recordCount(
        medicationId: MedicationId,
        quantity: BigDecimal,
        note: String?,
    ): MedsResult<StockMovementId> {
        counts += CountCall(medicationId, quantity, note)
        return countResult
    }

    override suspend fun recordRefill(
        medicationId: MedicationId,
        quantity: BigDecimal,
    ): MedsResult<StockMovementId> {
        refills += RefillCall(medicationId, quantity)
        return refillResult
    }

    override suspend fun recordDiscard(
        medicationId: MedicationId,
        quantity: BigDecimal,
        reason: String,
    ): MedsResult<StockMovementId> {
        discards += DiscardCall(medicationId, quantity, reason)
        return discardResult
    }
}

class FakeCareCircleRepository : CareCircleRepository {
    val circleFlow = MutableStateFlow<CareCircle?>(testCircle)
    override val circle: Flow<CareCircle?> = circleFlow

    val roleFlow = MutableStateFlow<MemberRole?>(MemberRole.OWNER)
    override val myRole: Flow<MemberRole?> = roleFlow

    val membersFlow = MutableStateFlow(listOf(meMember, momMember))
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
