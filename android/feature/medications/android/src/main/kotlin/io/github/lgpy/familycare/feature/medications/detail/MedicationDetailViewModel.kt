package io.github.lgpy.familycare.feature.medications.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import io.github.lgpy.familycare.feature.medications.data.StockOverview
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.StockMovementKind
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.parseStockQuantity
import io.github.lgpy.familycare.feature.medications.ui.stockErrors
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One medication (docs/ux/screens.md §3, docs/build-plan.md M3 "versioned regimen
 * changes", M5 "stock"): its details, the current schedule, the earlier versions,
 * the stock ledger's summary and the most recent doses, plus the three dosing
 * actions and the three stock actions.
 *
 * Stopping asks for a last day, which defaults to today and is clamped to the
 * current version's own bounds (never before its start, never after an existing
 * end). The repository applies [io.github.lgpy.familycare.feature.medications.domain.RegimenRules.stop]
 * and sets `stopped_at` in one transaction.
 *
 * Stock (D28) is read as one [StockOverview] from [StockRepository], so the
 * screen never adds up the ledger: refill, recount and discard are one local
 * insert each, and the summary comes back through the same watch.
 */
class MedicationDetailViewModel(
    private val medicationId: String,
    private val medicationRepository: MedicationRepository,
    doseLogRepository: DoseLogRepository,
    private val stockRepository: StockRepository,
    careCircleRepository: CareCircleRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> Instant = { Instant.now() },
) : ViewModel() {

    private val local = MutableStateFlow(MedicationDetailUiState())

    val uiState: StateFlow<MedicationDetailUiState> = combine(
        medicationRepository.observeMedication(MedicationId(medicationId)),
        doseLogRepository.observeMedicationEntries(MedicationId(medicationId)),
        stockRepository.observeOverview(MedicationId(medicationId)),
        careCircleRepository.myRole,
        local,
    ) { loaded, entries, stock, role, state ->
        state.copy(
            loaded = true,
            notFound = loaded == null,
            canWrite = role.canWrite,
            medication = loaded,
            stopped = loaded?.medication?.isStoppedAt(now()) == true,
            stock = stock,
            recentEntries = entries
                .sortedWith(compareByDescending<DoseLogEntry> { it.scheduledLocal }.thenByDescending { it.actualAt })
                .take(MedicationDetailUiState.RECENT_ENTRIES),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MedicationDetailUiState(),
    )

    /** Opens the stop dialog with a last day the domain will accept. */
    fun requestStop() {
        val current = uiState.value.medication?.currentRegimen ?: return
        val lastDay = when {
            today().isBefore(current.startDate) -> current.startDate
            current.endDate != null && today().isAfter(current.endDate) -> current.endDate
            else -> today()
        }
        local.update { it.copy(showStop = true, stopLastDay = lastDay.toString(), stopDateInvalid = false) }
    }

    fun dismissStop() = local.update { it.copy(showStop = false) }

    fun onLastDayChanged(value: String) =
        local.update { it.copy(stopLastDay = value, stopDateInvalid = false) }

    fun clearError() = local.update { it.copy(error = null) }

    /** Stops the medication from [MedicationDetailUiState.stopLastDay]. */
    fun confirmStop() {
        val state = uiState.value
        val loaded = state.medication ?: return
        val current = loaded.currentRegimen ?: return
        if (!state.canWrite || state.stopping) return

        val lastDay = runCatching { LocalDate.parse(state.stopLastDay.trim()) }.getOrNull()
        if (lastDay == null) {
            local.update { it.copy(stopDateInvalid = true) }
            return
        }

        local.update { it.copy(stopping = true, error = null) }
        viewModelScope.launch {
            when (
                val result = medicationRepository.stop(
                    medication = loaded.medication,
                    regimen = current,
                    lastDay = lastDay,
                )
            ) {
                is MedsResult.Success<*> -> local.update {
                    it.copy(stopping = false, showStop = false)
                }

                is MedsResult.Failure -> local.update {
                    it.copy(
                        stopping = false,
                        stopDateInvalid = result.error is MedsError.Invalid,
                        error = result.uiError(),
                    )
                }
            }
        }
    }

    // --- Pill look (D33) -----------------------------------------------------

    /** Opens the pill-look sheet (the same one the new-medication form uses). */
    fun requestPillLook() {
        if (!uiState.value.canWrite) return
        local.update { it.copy(showPillLook = true) }
    }

    fun dismissPillLook() = local.update { it.copy(showPillLook = false) }

    /**
     * Saves the look the sheet returned through
     * [MedicationRepository.update], so every other column of the medication
     * (name, strength, form, stock unit, instructions, prescription, policy,
     * stopped state) stays exactly as it was.
     *
     * A viewer or a second save while one is in flight never reaches the
     * repository. The sheet closes immediately, like the form's; a failure
     * shows the usual message on the screen.
     */
    fun savePillLook(look: PillLook) {
        val state = uiState.value
        val loaded = state.medication ?: return
        if (!state.canWrite || state.savingPillLook) return

        local.update { it.copy(savingPillLook = true, showPillLook = false, error = null) }
        viewModelScope.launch {
            when (val result = medicationRepository.update(loaded.medication.copy(pillLook = look))) {
                is MedsResult.Success<*> -> local.update { it.copy(savingPillLook = false) }
                is MedsResult.Failure -> local.update {
                    it.copy(savingPillLook = false, error = result.uiError())
                }
            }
        }
    }

    // --- Stock (D28) ---------------------------------------------------------

    /** Opens the refill dialog: stock that came in. */
    fun requestRefill() = openStock(StockAction.REFILL)

    /** Opens the recount dialog: "there are N now". */
    fun requestRecount() = openStock(StockAction.RECOUNT)

    /** Opens the discard dialog: stock thrown away, with a reason. */
    fun requestDiscard() = openStock(StockAction.DISCARD)

    fun dismissStock() =
        local.update { it.copy(stockAction = null, stockErrors = emptySet()) }

    fun onStockQuantityChanged(value: String) =
        local.update {
            it.copy(stockQuantity = value, stockErrors = it.stockErrors - MedicationField.STOCK_QUANTITY)
        }

    fun onStockReasonChanged(value: String) =
        local.update {
            it.copy(stockReason = value, stockErrors = it.stockErrors - MedicationField.STOCK_REASON)
        }

    /**
     * Writes the open dialog's movement. A viewer, an unparsable quantity, an
     * out-of-range one or a discard with no reason never reaches the repository;
     * the domain's own [stockErrors] mark the input otherwise.
     */
    fun confirmStock() {
        val state = uiState.value
        val action = state.stockAction ?: return
        val loaded = state.medication ?: return
        if (!state.canWrite || state.recordingStock) return

        val kind = when (action) {
            StockAction.REFILL -> StockMovementKind.REFILL
            StockAction.RECOUNT -> StockMovementKind.COUNT
            StockAction.DISCARD -> StockMovementKind.DISCARD
        }

        val quantity = parseStockQuantity(state.stockQuantity)
        if (quantity == null) {
            local.update { it.copy(stockErrors = setOf(MedicationField.STOCK_QUANTITY)) }
            return
        }
        val errors = stockErrors(quantity, kind, state.stockReason)
        if (errors.isNotEmpty()) {
            local.update { it.copy(stockErrors = errors) }
            return
        }

        val reason = state.stockReason.trim()
        local.update { it.copy(recordingStock = true, error = null, stockErrors = emptySet()) }
        viewModelScope.launch {
            val result = when (action) {
                StockAction.REFILL -> stockRepository.recordRefill(loaded.id, quantity)
                StockAction.RECOUNT -> stockRepository.recordCount(loaded.id, quantity)
                StockAction.DISCARD -> stockRepository.recordDiscard(loaded.id, quantity, reason)
            }
            when (result) {
                is MedsResult.Success<*> -> local.update {
                    it.copy(recordingStock = false, stockAction = null, stockQuantity = "", stockReason = "")
                }

                is MedsResult.Failure -> local.update {
                    it.copy(
                        recordingStock = false,
                        stockErrors = (result.error as? MedsError.Invalid)?.fields.orEmpty(),
                        error = result.uiError(),
                    )
                }
            }
        }
    }

    private fun openStock(action: StockAction) {
        if (!uiState.value.canWrite) return
        local.update {
            it.copy(
                stockAction = action,
                stockQuantity = "",
                stockReason = "",
                stockErrors = emptySet(),
                error = null,
            )
        }
    }
}

/** Which stock dialog is open on the detail screen (D28). */
enum class StockAction { REFILL, RECOUNT, DISCARD }

/** What the medication detail screen shows. */
data class MedicationDetailUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val canWrite: Boolean = false,
    val medication: MedicationWithRegimens? = null,
    /** `stopped_at` is set and not in the future; a pending stop is still active. */
    val stopped: Boolean = false,
    /** The ledger, its summary and the medication, from [StockRepository] (D28). */
    val stock: StockOverview? = null,
    val recentEntries: List<DoseLogEntry> = emptyList(),
    val showStop: Boolean = false,
    val stopLastDay: String = "",
    val stopDateInvalid: Boolean = false,
    val stopping: Boolean = false,
    /** The pill-look sheet is open (D33). */
    val showPillLook: Boolean = false,
    /** A look returned by the sheet is being written. */
    val savingPillLook: Boolean = false,
    /** The stock dialog that is open, if any. */
    val stockAction: StockAction? = null,
    /** The quantity typed in that dialog, kept as text while it is typed. */
    val stockQuantity: String = "",
    /** The discard reason typed in that dialog. */
    val stockReason: String = "",
    /** The stock inputs the domain rejected. */
    val stockErrors: Set<MedicationField> = emptySet(),
    val recordingStock: Boolean = false,
    val error: MedsUiError? = null,
) {

    /** Earlier versions, oldest first ("5 mg until 3 March"). */
    val history: List<io.github.lgpy.familycare.feature.medications.domain.Regimen>
        get() = medication?.history.orEmpty()

    /** The ledger's summary, or null while it has not loaded. */
    val stockSummary: io.github.lgpy.familycare.feature.medications.domain.StockSummary?
        get() = stock?.summary

    companion object {
        const val RECENT_ENTRIES: Int = 12
    }
}
