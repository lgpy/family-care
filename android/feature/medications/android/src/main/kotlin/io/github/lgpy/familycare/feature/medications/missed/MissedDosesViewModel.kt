package io.github.lgpy.familycare.feature.medications.missed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.dashboard.DoseGroupUi
import io.github.lgpy.familycare.feature.medications.dashboard.toUi
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.RECENT_DOSE_DAYS
import io.github.lgpy.familycare.feature.medications.data.TodayDoseGroup
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The missed-doses list (docs/ux/mockups/missed-doses.html): dose groups of the
 * [RECENT_DOSE_DAYS] days before today that nobody confirmed, so a caregiver who
 * forgot to tap "Give" can still record them.
 *
 * "It was given" opens the dose-slot screen, which records a catch-up at the
 * scheduled time and keeps the who-gave-it and per-medicine choices. "Not given"
 * records every still-open slot of the group as skipped at once; there is no
 * reason to pick, so it is stored as [SkipReason.OTHER], the same value the
 * dose-slot screen uses for an unanswered skip.
 *
 * A group the caregiver acted on stays on the list, folded to its result, until
 * the screen closes, so a tap never makes a card jump away under the finger.
 */
class MissedDosesViewModel(
    private val doseLogRepository: DoseLogRepository,
    medicationRepository: MedicationRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<MissedDosesUiState> = combine(
        local,
        doseLogRepository.recentDoses,
        medicationRepository.activeMedications,
        careCircleRepository.myRole,
        careCircleRepository.members,
    ) { state, groups, medications, role, members ->
        val patientNames = medications.associate { it.patient.patientId to it.patient.displayName }
        val memberNames = members.associate { it.userId to it.displayName }
        val myUserId = members.firstOrNull { it.isMe }?.userId
        val pillLooks = medications.associate { it.medication.id.value to it.medication.pillLook }
        val shown = groups
            .filter { group -> group.isMissed() || group.key() in state.touched }
            .map { group ->
                MissedGroupUi(
                    dose = group.toUi(
                        patientName = patientNames[group.patientId].orEmpty(),
                        memberNames = memberNames,
                        myUserId = myUserId,
                        pillLooks = pillLooks,
                    ),
                    open = group.isMissed(),
                )
            }
        MissedDosesUiState(
            loaded = true,
            canWrite = role.canWrite,
            days = shown
                .groupBy { it.dose.scheduledLocal.toLocalDate() }
                .map { (date, dayGroups) -> MissedDay(date = date, groups = dayGroups) },
            saving = state.saving,
            error = state.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MissedDosesUiState(),
    )

    /** "It was given": keeps the card on the list; the screen opens the dose slot. */
    fun onGiven(patientId: PatientId, scheduledLocal: LocalDateTime) {
        local.update { it.copy(touched = it.touched + (patientId to scheduledLocal)) }
    }

    /** "Not given": every still-open slot of the group becomes a skipped entry. */
    fun onNotGiven(patientId: PatientId, scheduledLocal: LocalDateTime) {
        val state = uiState.value
        if (!state.canWrite || state.saving) return
        val group = state.days
            .flatMap { it.groups }
            .firstOrNull { it.dose.patientId == patientId && it.dose.scheduledLocal == scheduledLocal }
            ?.takeIf { it.open }
            ?.dose
            ?.group
            ?: return
        val open = group.statuses.filterNot { it.isConfirmed }.map { it.slot }
        if (open.isEmpty()) return

        local.update {
            it.copy(touched = it.touched + (patientId to scheduledLocal), saving = true, error = null)
        }
        viewModelScope.launch {
            val result = doseLogRepository.confirm(
                group = group.group.copy(slots = open),
                skipped = open.associate { it.slotId to SkipReason.OTHER },
                actualAt = open.first().instant,
            )
            local.update { it.copy(saving = false, error = result.uiError()) }
        }
    }

    fun clearError() {
        local.update { it.copy(error = null) }
    }

    private data class Local(
        val touched: Set<Pair<PatientId, LocalDateTime>> = emptySet(),
        val saving: Boolean = false,
        val error: MedsUiError? = null,
    )
}

private fun TodayDoseGroup.isMissed(): Boolean = statuses.any { it.state == DoseState.MISSED }

private fun TodayDoseGroup.key(): Pair<PatientId, LocalDateTime> = patientId to scheduledLocal

/** What the list shows: the days with something missed, newest first. */
data class MissedDosesUiState(
    val loaded: Boolean = false,
    val canWrite: Boolean = false,
    val days: List<MissedDay> = emptyList(),
    val saving: Boolean = false,
    val error: MedsUiError? = null,
)

/** One day's groups, oldest time first. */
data class MissedDay(
    val date: LocalDate,
    val groups: List<MissedGroupUi>,
)

/**
 * One patient's doses at one time. [open] is false once every slot was
 * recorded; the card then folds to "given" or "not given".
 */
data class MissedGroupUi(
    val dose: DoseGroupUi,
    val open: Boolean,
)
