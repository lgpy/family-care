package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.TodayDoseGroup
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The one-line state of a dose group on Today. */
enum class DoseGroupState {
    UPCOMING,
    DUE,
    OVERDUE,
    MISSED,
    TAKEN,
    SKIPPED,

    /** Every slot is confirmed, but not all the same way (some taken, some skipped). */
    PARTLY,
}

/** Who recorded the confirmations of a group, resolved from the entry. */
data class GivenByUi(
    val name: String?,
    val isMe: Boolean,
    val someoneElse: Boolean,
)

/** One "Doses today" row: a patient's doses at one wall-clock time. */
data class DoseGroupUi(
    val group: TodayDoseGroup,
    val patientName: String,
    val state: DoseGroupState,
    val givenBy: GivenByUi?,
    val at: LocalTime?,
) {

    val patientId: PatientId get() = group.patientId

    val scheduledLocal: LocalDateTime get() = group.scheduledLocal

    /** The wall-clock time, e.g. `08:00`. */
    val time: LocalTime get() = scheduledLocal.toLocalTime()

    val doseCount: Int get() = group.slots.size

    /** Every slot has a taken or skipped entry. */
    val allConfirmed: Boolean get() = group.allConfirmed

    /** At least one slot already has an effective confirmation. */
    val hasConfirmation: Boolean get() = group.statuses.any { it.isConfirmed }

    /** The group confirmation target, from the data layer's view. */
    val timeSlotGroup: io.github.lgpy.familycare.feature.medications.domain.TimeSlotGroup
        get() = group.group
}

/**
 * The "Doses today" card: every active medication's slot for today, grouped per
 * patient and time, with the state the domain derived and a one-tap "Give ✓".
 *
 * Group confirmation is a UI convenience: [DoseLogRepository.confirm] still
 * writes one entry per medication. A group that already has an effective
 * confirmation is not given again in one tap; the card asks first
 * ([DosesTodayUiState.warning]) because a second entry is a double dose
 * (docs/domain/medications.md "Double confirmation").
 *
 * A viewer sees the same rows without the button, and the repository refuses the
 * write anyway.
 */
class DosesTodayViewModel(
    private val doseLogRepository: DoseLogRepository,
    medicationRepository: MedicationRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val local = MutableStateFlow(DosesTodayUiState())

    val uiState: StateFlow<DosesTodayUiState> = combine(
        local,
        doseLogRepository.dosesToday,
        medicationRepository.activeMedications,
        careCircleRepository.myRole,
        careCircleRepository.members,
    ) { state, groups, medications, role, members ->
        val patientNames = medications.associate { it.patient.patientId to it.patient.displayName }
        val memberNames = members.associate { it.userId to it.displayName }
        val myUserId = members.firstOrNull { it.isMe }?.userId
        state.copy(
            loaded = true,
            canWrite = role.canWrite,
            groups = groups.map { group ->
                group.toUi(patientNames[group.patientId].orEmpty(), memberNames, myUserId)
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DosesTodayUiState(),
    )

    /** "Give ✓" for a whole group, or the double-confirmation warning first. */
    fun give(patientId: PatientId, scheduledLocal: LocalDateTime) {
        val group = uiState.value.groups.firstOrNull {
            it.patientId == patientId && it.scheduledLocal == scheduledLocal
        } ?: return
        if (uiState.value.saving) return
        if (group.hasConfirmation) {
            local.update { it.copy(warning = group) }
            return
        }
        save(group)
    }

    /** The second tap of the double-confirmation dialog: writes another entry. */
    fun giveAnyway() {
        val group = uiState.value.warning ?: return
        local.update { it.copy(warning = null) }
        save(group)
    }

    fun dismissWarning() {
        local.update { it.copy(warning = null) }
    }

    fun clearError() {
        local.update { it.copy(error = null) }
    }

    private fun save(group: DoseGroupUi) {
        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = doseLogRepository.confirm(group.timeSlotGroup, givenBy = GivenBy.Me)
            local.update { it.copy(saving = false, error = result.uiError()) }
        }
    }
}

/** What the card shows: rows, whether the button exists, and dialog state. */
data class DosesTodayUiState(
    val groups: List<DoseGroupUi> = emptyList(),
    val canWrite: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    /** A group whose doses were already confirmed; the dialog is showing. */
    val warning: DoseGroupUi? = null,
    val error: MedsUiError? = null,
)

/** Translates one domain group into what the card draws. */
internal fun TodayDoseGroup.toUi(
    patientName: String,
    memberNames: Map<String, String?>,
    myUserId: String?,
): DoseGroupUi {
    val latest = statuses.flatMap { it.effectiveEntries }.maxByOrNull { it.actualAt }
    return DoseGroupUi(
        group = this,
        patientName = patientName,
        state = aggregateState(statuses.map { it.state }),
        givenBy = latest?.toGivenByUi(memberNames, myUserId),
        at = latest?.actualAt?.atZone(ZoneId.systemDefault())?.toLocalTime(),
    )
}

/**
 * The most urgent state of the group: missed beats overdue beats due beats
 * upcoming, and an unconfirmed slot hides the taken ones (there is still
 * something to do).
 */
private fun aggregateState(states: List<DoseState>): DoseGroupState {
    if (states.isEmpty()) return DoseGroupState.UPCOMING
    val live = states.filterNot { it == DoseState.TAKEN || it == DoseState.SKIPPED }
    if (live.isEmpty()) {
        return when {
            states.all { it == DoseState.TAKEN } -> DoseGroupState.TAKEN
            states.all { it == DoseState.SKIPPED } -> DoseGroupState.SKIPPED
            else -> DoseGroupState.PARTLY
        }
    }
    return when {
        DoseState.MISSED in live -> DoseGroupState.MISSED
        DoseState.OVERDUE in live -> DoseGroupState.OVERDUE
        DoseState.DUE in live -> DoseGroupState.DUE
        else -> DoseGroupState.UPCOMING
    }
}

private fun DoseLogEntry.toGivenByUi(
    memberNames: Map<String, String?>,
    myUserId: String?,
): GivenByUi {
    if (givenByOther) {
        return GivenByUi(name = givenByName, isMe = false, someoneElse = true)
    }
    val userId = givenByUserId
    return GivenByUi(
        name = userId?.let { memberNames[it] },
        isMe = userId != null && userId == myUserId,
        someoneElse = false,
    )
}
