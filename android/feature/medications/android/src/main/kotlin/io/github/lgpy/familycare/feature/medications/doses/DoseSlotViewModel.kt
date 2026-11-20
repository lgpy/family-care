package io.github.lgpy.familycare.feature.medications.doses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.TodayDoseGroup
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseSlot
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.MemberOrder
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who the "Given by" picker points at. */
enum class GivenByChoice { Me, Member, SomeoneElse }

/**
 * What the bottom bar's one button will do, derived from the toggles.
 *
 * Kept as a value (not a formatted string) so the label state is unit-tested on
 * the JVM: the screen turns it into "Give all 3", "Give 2 · skip 1" or
 * "Skip all" from the string resources.
 */
sealed interface GiveSummary {

    /** Every pill is checked: "Give all [count]". */
    data class GiveAll(val count: Int) : GiveSummary

    /** Some pills are unchecked: "Give [given] · skip [skipped]". */
    data class GiveSome(val given: Int, val skipped: Int) : GiveSummary

    /** Every pill is unchecked: "Skip all". */
    data class SkipAll(val count: Int) : GiveSummary
}

/** One medication of the slot, as the screen draws it. */
data class SlotMedicationUi(
    val slot: DoseSlot,
    val name: String,
    val strength: String?,
    /** The stored unit token ("tablet", "ml"); the screen localizes it. */
    val stockUnit: String,
    val instructions: String?,
    val pillLook: PillLook?,
    val outcome: DoseOutcome,
    val skipReason: SkipReason?,
    /** The effective entry already recorded for this slot, if any. */
    val existing: DoseLogEntry?,
) {
    /** Whether saving would write or correct anything for this medication. */
    val isDirty: Boolean
        get() = existing == null ||
            existing.outcome != outcome ||
            existing.skipReason != skipReason
}

/**
 * The dose-slot screen (docs/ux/screens.md §2, screens v2 "Give"): one patient's
 * doses at one wall-clock time, with an exception per medication.
 *
 * Every pill starts checked. Unchecking one marks it skipped with no reason
 * yet: the screen asks for one, and a pill saved without an answer is recorded
 * as [SkipReason.OTHER], never as a guessed "refused". Checking it again clears
 * the reason. Saving is the group confirmation the card does in one
 * tap: the not-yet-confirmed slots go in **one** [DoseLogRepository.confirm]
 * call, and a slot that already has an entry is **corrected**
 * ([DoseLogRepository.correct]) rather than confirmed again — a second entry for
 * the same slot would read as a double dose.
 *
 * A slot whose entries are all recorded opens read-only ([DoseSlotUiState
 * .confirmed]), showing who gave each dose and when; [onEdit] unlocks the
 * toggles so the existing correction path stays reachable.
 */
class DoseSlotViewModel(
    private val patientId: String,
    private val scheduledLocal: LocalDateTime,
    private val doseLogRepository: DoseLogRepository,
    medicationRepository: MedicationRepository,
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val local = MutableStateFlow(DoseSlotUiState())

    /** The user's choices per regimen, layered over the stored entries. */
    private val edits = MutableStateFlow<Map<String, SlotEdit>>(emptyMap())

    private val base = combine(
        doseLogRepository.dosesToday,
        medicationRepository.activeMedications,
        patientRepository.observe(PatientId(patientId)),
        careCircleRepository.myRole,
        careCircleRepository.members,
    ) { groups, medications, patient, role, members ->
        BaseState(
            group = groups.firstOrNull { group ->
                group.patientId == PatientId(patientId) && group.scheduledLocal == scheduledLocal
            },
            medications = medications.associate { it.medication.id.value to it.medication },
            patientName = patient?.displayName,
            canWrite = role.canWrite,
            members = members.sortedWith(MemberOrder),
        )
    }

    val uiState: StateFlow<DoseSlotUiState> = combine(base, local, edits) { baseState, state, edits ->
        val items = baseState.group?.toItems(baseState.medications, edits).orEmpty()
        state.copy(
            loaded = true,
            group = baseState.group,
            notFound = baseState.group == null,
            patientName = baseState.patientName,
            canWrite = baseState.canWrite,
            members = baseState.members,
            items = items,
            confirmed = !state.editing && items.isNotEmpty() && items.all { it.existing != null },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DoseSlotUiState(),
    )

    /**
     * Checks or unchecks one pill: a checked pill becomes skipped (keeping a
     * reason already chosen, else none) and an unchecked one becomes given again.
     */
    fun onToggle(regimenIdValue: String) {
        val current = uiState.value.items.firstOrNull { it.slot.regimenId.value == regimenIdValue }
            ?: return
        edits.update { edits ->
            val edit = edits[regimenIdValue] ?: SlotEdit()
            val toggled = if (current.outcome == DoseOutcome.TAKEN) {
                edit.copy(
                    outcome = DoseOutcome.SKIPPED,
                    skipReason = edit.skipReason,
                )
            } else {
                edit.copy(outcome = DoseOutcome.TAKEN, skipReason = null)
            }
            edits + (regimenIdValue to toggled)
        }
        local.update { it.copy(editing = true) }
    }

    fun onOutcome(regimenIdValue: String, outcome: DoseOutcome) {
        edits.update { current ->
            val edit = current[regimenIdValue] ?: SlotEdit()
            current + (regimenIdValue to edit.copy(
                outcome = outcome,
                skipReason = if (outcome == DoseOutcome.SKIPPED) {
                    edit.skipReason
                } else {
                    null
                },
            ))
        }
        local.update { it.copy(editing = true) }
    }

    fun onSkipReason(regimenIdValue: String, reason: SkipReason) {
        edits.update { current ->
            val edit = current[regimenIdValue] ?: SlotEdit()
            current + (regimenIdValue to edit.copy(
                outcome = DoseOutcome.SKIPPED,
                skipReason = reason,
            ))
        }
        local.update { it.copy(editing = true) }
    }

    /** Unlocks a fully recorded slot so its entries can be corrected. */
    fun onEdit() {
        local.update { it.copy(editing = true) }
    }

    fun onGivenByMe() {
        local.update { it.copy(givenBy = GivenByChoice.Me, memberId = null) }
    }

    fun onGivenByMember(userId: String) {
        local.update { it.copy(givenBy = GivenByChoice.Member, memberId = userId) }
    }

    fun onGivenBySomeoneElse() {
        local.update { it.copy(givenBy = GivenByChoice.SomeoneElse) }
    }

    fun onSomeoneElseName(value: String) {
        local.update { it.copy(someoneName = value) }
    }

    fun onNoteChanged(value: String) {
        local.update { it.copy(note = value) }
    }

    fun clearError() {
        local.update { it.copy(error = null) }
    }

    /**
     * Writes the group: one confirm call for the untouched slots, one correct
     * call per changed, already-recorded slot.
     */
    fun save() {
        val state = uiState.value
        if (!state.canWrite || state.saving || !state.loaded || state.notFound) return
        val group = state.group ?: return

        val pending = state.items.filter { it.existing == null }
        val corrections = state.items.filter { it.existing != null && it.isDirty }
        if (pending.isEmpty() && corrections.isEmpty()) {
            local.update { it.copy(saved = true) }
            return
        }

        val givenBy = state.givenBy()
        val note = state.note.trim().takeIf { it.isNotEmpty() }
        local.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            var error: MedsUiError? = null
            if (pending.isNotEmpty()) {
                val skipped = pending
                    .filter { it.outcome == DoseOutcome.SKIPPED }
                    .associate { it.slot.slotId to (it.skipReason ?: SkipReason.OTHER) }
                val result = doseLogRepository.confirm(
                    group = group.group.copy(slots = pending.map { it.slot }),
                    skipped = skipped,
                    givenBy = givenBy,
                    note = note,
                )
                error = result.uiError()
            }
            if (error == null) {
                corrections.forEach { item ->
                    if (error != null) return@forEach
                    val entry = item.existing ?: return@forEach
                    val result = doseLogRepository.correct(
                        entry = entry,
                        outcome = item.outcome,
                        skipReason = if (item.outcome == DoseOutcome.SKIPPED) {
                            item.skipReason ?: SkipReason.OTHER
                        } else {
                            null
                        },
                        actualQuantity = if (item.outcome == DoseOutcome.TAKEN) item.slot.quantity else null,
                        givenBy = givenBy,
                        note = note,
                    )
                    error = result.uiError()
                }
            }
            local.update { it.copy(saving = false, saved = error == null, error = error) }
        }
    }
}

/**
 * A user's choice for one slot. [outcome] is null until the screen is touched,
 * so the stored entry (or "taken") decides.
 */
data class SlotEdit(
    val outcome: DoseOutcome? = null,
    val skipReason: SkipReason? = null,
)

/** What the screen shows and what a save would write. */
data class DoseSlotUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val patientName: String? = null,
    val items: List<SlotMedicationUi> = emptyList(),
    val canWrite: Boolean = false,
    val members: List<CircleMember> = emptyList(),
    val givenBy: GivenByChoice = GivenByChoice.Me,
    val memberId: String? = null,
    val someoneName: String = "",
    val note: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: MedsUiError? = null,
    /** True once the user has touched a pill or asked to correct a saved slot. */
    val editing: Boolean = false,
    /** True when every slot already has an entry and nothing was edited. */
    val confirmed: Boolean = false,
    /** The domain group being edited; null until the rows arrive. */
    val group: TodayDoseGroup? = null,
) {

    /** How many pills the button would give. */
    val givenCount: Int get() = items.count { it.outcome == DoseOutcome.TAKEN }

    /** How many pills the button would skip. */
    val skippedCount: Int get() = items.count { it.outcome == DoseOutcome.SKIPPED }

    /** The bottom bar's action, or null when the slot has no rows. */
    val giveSummary: GiveSummary?
        get() = when {
            items.isEmpty() -> null
            skippedCount == 0 -> GiveSummary.GiveAll(givenCount)
            givenCount == 0 -> GiveSummary.SkipAll(skippedCount)
            else -> GiveSummary.GiveSome(given = givenCount, skipped = skippedCount)
        }

    /** The choice as the data layer wants it. */
    fun givenBy(): GivenBy = when (givenBy) {
        GivenByChoice.Me -> GivenBy.Me
        GivenByChoice.Member -> memberId?.let { GivenBy.Member(it) } ?: GivenBy.Me
        GivenByChoice.SomeoneElse ->
            GivenBy.SomeoneElse(someoneName.trim().takeIf { it.isNotEmpty() })
    }
}

private data class BaseState(
    val group: TodayDoseGroup?,
    val medications: Map<String, Medication>,
    val patientName: String?,
    val canWrite: Boolean,
    val members: List<CircleMember>,
)

private fun TodayDoseGroup.toItems(
    medications: Map<String, Medication>,
    edits: Map<String, SlotEdit>,
): List<SlotMedicationUi> = slots.map { slot ->
    val existing = statusOf(slot)?.effectiveEntries?.lastOrNull()
    val edit = edits[slot.regimenId.value]
    val outcome = edit?.outcome ?: existing?.outcome ?: DoseOutcome.TAKEN
    val medication = medications[slot.medicationId.value]
    SlotMedicationUi(
        slot = slot,
        name = medication?.name.orEmpty(),
        strength = medication?.strength,
        stockUnit = medication?.stockUnit.orEmpty(),
        instructions = medication?.instructions,
        pillLook = medication?.pillLook,
        outcome = outcome,
        skipReason = when (outcome) {
            DoseOutcome.SKIPPED -> edit?.skipReason ?: existing?.skipReason
            DoseOutcome.TAKEN -> null
        },
        existing = existing,
    )
}
