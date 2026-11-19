package io.github.lgpy.familycare.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientExportFormat
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.circle.canManage
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.ui.export.PatientExportOutcome
import io.github.lgpy.familycare.core.ui.export.PatientExporter
import io.github.lgpy.familycare.core.ui.patient.TimelineEntry
import io.github.lgpy.familycare.core.ui.patient.TimelineSources
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One month of the History tab: its events, newest first. */
data class HistoryGroup(
    val month: YearMonth,
    val entries: List<TimelineEntry>,
)

/** A generated export waiting for the share sheet. */
data class ExportShare(
    val file: java.io.File,
    val format: PatientExportFormat,
)

/**
 * The patient profile (D29, docs/ux/screens.md §3).
 *
 * [history] is every module's [TimelineSources] merged, newest first and
 * grouped by month; [canWrite] decides whether the "Edit patient" action
 * exists (the same [canWrite] rule the form uses, false for an archived
 * patient) and [canManage] whether the owner-only Archive and Delete actions
 * exist (D30).
 */
data class PatientProfileUiState(
    val loaded: Boolean = false,
    /** The id was given but the patient is not in the local database (anymore). */
    val notFound: Boolean = false,
    val displayName: String = "",
    /** The year of birth the head derives the age from; null when unknown. */
    val birthYear: Int? = null,
    /** The reminder line under the name, from the patient's policy. */
    val reminderPolicy: ReminderPolicy? = null,
    /** The name of the caregiver reminded first, when there is one. */
    val primaryCaregiverName: String? = null,
    /** The caregiver reminded first is the signed-in user ("You first, then everyone"). */
    val primaryCaregiverIsMe: Boolean = false,
    val canWrite: Boolean = false,
    val canManage: Boolean = false,
    val isArchived: Boolean = false,
    /** The last archive/unarchive/erase failure, to show once. */
    val actionError: CoreError? = null,
    /** An erase is in flight (the button shows progress and the dialog stays). */
    val erasing: Boolean = false,
    /** The patient was erased: the screen navigates back to the list. */
    val erased: Boolean = false,
    /** Owners and caregivers may export; viewers never (D30). */
    val canExport: Boolean = false,
    /** An export is being generated (the dialog/screen shows progress). */
    val exporting: Boolean = false,
    /** The last export failure (offline, not allowed, generation failed). */
    val exportError: CoreError? = null,
    /** The generated file to hand to the share sheet, or null. */
    val exportShare: ExportShare? = null,
    val history: List<HistoryGroup> = emptyList(),
)

/**
 * The patient profile's state: the patient, the role and the merged history.
 *
 * The screen owns no history of its own; every source is a module's flow, so a
 * local write (a regimen change, a new appointment) refreshes the tab without
 * the shell knowing which module produced it.
 *
 * Archive, unarchive and erase (D30) are the owner's lifecycle actions; the
 * screen only asks for confirmation. Erase needs the typed name, which is the
 * screen's check, so a mis-tap cannot delete a person here.
 *
 * [zone] is the phone's zone: months are the user's months, not UTC's, and a
 * test can pin it.
 */
class PatientProfileViewModel(
    patientId: String,
    private val patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
    timelineSources: TimelineSources,
    private val exporter: PatientExporter,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val id = PatientId(patientId)

    private data class ActionState(
        val error: CoreError? = null,
        val erasing: Boolean = false,
        val erased: Boolean = false,
        val exporting: Boolean = false,
        val exportError: CoreError? = null,
        val exportShare: ExportShare? = null,
    )

    private val action = MutableStateFlow(ActionState())

    val uiState: StateFlow<PatientProfileUiState> = combine(
        patientRepository.observe(id),
        careCircleRepository.myRole,
        careCircleRepository.members,
        timelineSources.entries(id),
        action,
    ) { patient, role, members, entries, actionState ->
        PatientProfileUiState(
            loaded = true,
            notFound = patient == null,
            displayName = patient?.displayName.orEmpty(),
            birthYear = patient?.birthYear,
            reminderPolicy = patient?.reminderPolicy,
            primaryCaregiverName = patient?.primaryCaregiverId?.let { primaryId ->
                members.firstOrNull { it.userId == primaryId }?.displayName
            },
            primaryCaregiverIsMe = patient?.primaryCaregiverId?.let { primaryId ->
                members.firstOrNull { it.userId == primaryId }?.isMe
            } == true,
            canWrite = patient != null && role.canWrite(patient),
            canManage = role.canManage,
            isArchived = patient?.isArchived == true,
            canExport = patient != null && role?.canWrite == true,
            actionError = actionState.error,
            erasing = actionState.erasing,
            erased = actionState.erased,
            exporting = actionState.exporting,
            exportError = actionState.exportError,
            exportShare = actionState.exportShare,
            history = groupByMonth(entries, zone),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PatientProfileUiState(),
    )

    /** Archives the patient (owner only); the banner and read-only mode appear locally. */
    fun archive() = runAction { patientRepository.archive(id) }

    /** Clears the archive flag, making the patient editable again (owner only). */
    fun unarchive() = runAction { patientRepository.unarchive(id) }

    /**
     * Erases the patient for good (owner only). The screen has already checked
     * that the typed name matches; the server checks the role again.
     */
    fun erase() {
        action.update { it.copy(erasing = true, error = null) }
        viewModelScope.launch {
            when (val result = patientRepository.erase(id)) {
                is CoreResult.Success ->
                    action.update { it.copy(erasing = false, erased = true) }

                is CoreResult.Failure ->
                    action.update { it.copy(erasing = false, error = result.error) }
            }
        }
    }

    /** Dismisses the error line after the user has seen it. */
    fun clearActionError() {
        action.update { it.copy(error = null) }
    }

    /**
     * Generates [format] of the patient and leaves the file for the screen to
     * share (D30). The audit call happens inside [PatientExporter] **before**
     * the file, so an offline or refused export writes nothing.
     */
    fun export(format: PatientExportFormat) {
        if (action.value.exporting) return
        action.update { it.copy(exporting = true, exportError = null, exportShare = null) }
        viewModelScope.launch {
            when (val outcome = exporter.export(id, format)) {
                is PatientExportOutcome.Success ->
                    action.update {
                        it.copy(
                            exporting = false,
                            exportShare = ExportShare(outcome.file, outcome.format),
                        )
                    }

                is PatientExportOutcome.Failure ->
                    action.update {
                        it.copy(exporting = false, exportError = outcome.error)
                    }
            }
        }
    }

    /** The share sheet was opened (or the file is gone); it is not offered again. */
    fun onExportShared() {
        action.update { it.copy(exportShare = null) }
    }

    /** Dismisses the export error after the user has seen it. */
    fun clearExportError() {
        action.update { it.copy(exportError = null) }
    }

    private fun runAction(block: suspend () -> CoreResult<PatientId>) {
        action.update { it.copy(error = null) }
        viewModelScope.launch {
            when (val result = block()) {
                is CoreResult.Success -> Unit
                is CoreResult.Failure -> action.update { it.copy(error = result.error) }
            }
        }
    }

    companion object {

        /** Groups [entries] newest first into the months of [zone]. */
        fun groupByMonth(entries: List<TimelineEntry>, zone: ZoneId): List<HistoryGroup> =
            entries
                .sortedByDescending { entry -> entry.at }
                .groupBy { YearMonth.from(it.at.atZone(zone)) }
                .map { (month, monthEntries) -> HistoryGroup(month, monthEntries) }
    }
}
