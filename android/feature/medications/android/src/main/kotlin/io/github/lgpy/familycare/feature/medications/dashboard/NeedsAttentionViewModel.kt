package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.RECENT_DOSE_DAYS
import io.github.lgpy.familycare.feature.medications.data.TodayDoseGroup
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "Needs attention" card (docs/ux/screens.md §1, docs/domain/medications.md):
 * overdue and missed dose groups of the circle, ordered above "Doses today".
 *
 * Missed groups of the [RECENT_DOSE_DAYS] days before today are not rows of
 * their own: [NeedsAttentionUiState.missedEarlier] becomes one summary row that
 * opens the missed-doses list. Groups of those days that are still due or
 * overdue (a late-evening dose just after midnight) are rows of their own,
 * above today's, until they are confirmed or turn missed.
 *
 * It reuses the doses-today view of the data ([DoseLogRepository.dosesToday])
 * and the same group-confirm write; only the filter differs. A group that is
 * still unconfirmed cannot already have a confirmation, but the same
 * double-confirmation guard is kept so a sync arriving between the tap and the
 * save cannot cause a second entry.
 */
class NeedsAttentionViewModel(
    private val doseLogRepository: DoseLogRepository,
    medicationRepository: MedicationRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val local = MutableStateFlow(NeedsAttentionUiState())

    private val groupsFlow = combine(
        doseLogRepository.dosesToday,
        doseLogRepository.recentDoses,
    ) { today, recent -> today to recent }

    val uiState: StateFlow<NeedsAttentionUiState> = combine(
        local,
        groupsFlow,
        medicationRepository.activeMedications,
        careCircleRepository.myRole,
        careCircleRepository.members,
    ) { state, (groups, recent), medications, role, members ->
        val patientNames = medications.associate { it.patient.patientId to it.patient.displayName }
        val memberNames = members.associate { it.userId to it.displayName }
        val myUserId = members.firstOrNull { it.isMe }?.userId
        val pillLooks = medications.associate { it.medication.id.value to it.medication.pillLook }
        fun TodayDoseGroup.row() = toUi(
            patientName = patientNames[patientId].orEmpty(),
            memberNames = memberNames,
            myUserId = myUserId,
            pillLooks = pillLooks,
        )

        // A dose of an earlier day that is still due or overdue (a 23:00 dose at
        // 00:20) is not in today's list and not yet in the missed list: without
        // this row it would be on no screen. It leaves when it is confirmed or
        // turns missed (then the missed list has it).
        val earlierOpen = recent
            .map { it.row() }
            .filter { it.state == DoseGroupState.DUE || it.state == DoseGroupState.OVERDUE }
            .sortedBy { it.scheduledLocal }
        val todays = groups
            .map { it.row() }
            .filter { it.state == DoseGroupState.OVERDUE || it.state == DoseGroupState.MISSED }
        state.copy(
            loaded = true,
            canWrite = role.canWrite,
            groups = earlierOpen + todays,
            missedEarlier = recent
                .filter { group -> group.statuses.any { it.state == DoseState.MISSED } }
                .map { group ->
                    MissedEarlier(
                        patientId = group.patientId,
                        patientName = patientNames[group.patientId].orEmpty(),
                    )
                },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NeedsAttentionUiState(),
    )

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

/** What the card shows: overdue/missed groups and dialog state. */
data class NeedsAttentionUiState(
    val groups: List<DoseGroupUi> = emptyList(),
    val canWrite: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val warning: DoseGroupUi? = null,
    val error: MedsUiError? = null,
    /** One entry per missed group of the days before today, newest day first. */
    val missedEarlier: List<MissedEarlier> = emptyList(),
)

/** A missed group of an earlier day, as far as the summary row needs it. */
data class MissedEarlier(
    val patientId: PatientId,
    val patientName: String,
)
