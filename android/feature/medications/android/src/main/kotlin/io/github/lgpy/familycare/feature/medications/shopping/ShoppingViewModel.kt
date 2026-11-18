package io.github.lgpy.familycare.feature.medications.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.StockOverview
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.ShoppingStatus
import io.github.lgpy.familycare.feature.medications.domain.StockMovementKind
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.parseStockQuantity
import io.github.lgpy.familycare.feature.medications.ui.stockErrors
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One line of the shopping list (docs/ux/screens.md §5, D28).
 *
 * It is derived from a [StockOverview]: the medication and its patient, how long
 * the stock lasts, and whether a prescription has to be sorted out first. The
 * screen never decides that itself — [status] is the domain's
 * [ShoppingStatus].
 */
data class ShoppingItem(
    val medication: Medication,
    val patientName: String,
    val daysRemaining: Int?,
    val displayStock: BigDecimal,
    val status: ShoppingStatus,
) {

    /** The medication's id, which is also the list key. */
    val medicationId: String get() = medication.id.value

    /** The patient the medication belongs to, for opening its detail screen. */
    val patientId: String get() = medication.patientId.value

    /** What stock is counted in, for "How many tablet did you buy?". */
    val stockUnit: String get() = medication.stockUnit

    /** The box's strength, shown next to the name when known. */
    val strength: String? get() = medication.strength

    /** Whether this line is in the "needs a new prescription first" group. */
    val needsPrescription: Boolean get() = status == ShoppingStatus.NEEDS_PRESCRIPTION

    /** Whether the prescription is missing rather than expired (the subtitle). */
    val prescriptionMissing: Boolean
        get() = needsPrescription && medication.prescriptionValidUntil == null

    /** The current prescription's last valid day, when the medication has one. */
    val prescriptionValidUntil: LocalDate? get() = medication.prescriptionValidUntil
}

/**
 * The shopping list (docs/ux/screens.md §5, D28): the low, non-stopped
 * medications of every patient in the circle, split by the domain's
 * [ShoppingStatus] into "needs a new prescription first" and "ready to buy".
 *
 * The list is **derived**, never stored: [StockRepository.overviews] re-emits on
 * every local or synced change, so buying a refill or setting a new prescription
 * moves the line by itself — the screen never removes an item by hand.
 *
 * Writers buy ("how many did you buy?" → one refill) and record a new
 * prescription's last valid day (one `update` of the prescription column);
 * viewers see the same list with no actions, the same rule the repository
 * enforces.
 */
class ShoppingViewModel(
    private val stockRepository: StockRepository,
    private val medicationRepository: MedicationRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val local = MutableStateFlow(ShoppingUiState())

    /** True once the ledger watch has emitted, so an empty list is not "loading". */
    private val loaded = MutableStateFlow(false)

    private val lowOverviews = stockRepository.overviews
        .onEach { loaded.value = true }
        .map { overviews -> overviews.mapNotNull(::shoppingItem) }

    val uiState: StateFlow<ShoppingUiState> = combine(
        lowOverviews,
        loaded,
        careCircleRepository.myRole,
        local,
    ) { items, isLoaded, role, state ->
        val ordered = items.sortedWith(
            compareBy<ShoppingItem>(
                { it.daysRemaining ?: Int.MAX_VALUE },
                { it.medication.name.lowercase() },
            ),
        )
        state.copy(
            loaded = isLoaded,
            canWrite = role.canWrite,
            needsPrescription = ordered.filter { it.needsPrescription },
            readyToBuy = ordered.filter { !it.needsPrescription },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShoppingUiState(),
    )

    // --- Buying (D28) --------------------------------------------------------

    /** Opens "How many did you buy?" for [medicationId]; writers only. */
    fun requestBuy(medicationId: String) {
        if (!uiState.value.canWrite) return
        val item = item(medicationId) ?: return
        local.update {
            it.copy(buy = item, buyQuantity = "", buyErrors = emptySet(), error = null)
        }
    }

    fun dismissBuy() =
        local.update { it.copy(buy = null, buyQuantity = "", buyErrors = emptySet()) }

    fun onBuyQuantityChanged(value: String) =
        local.update {
            it.copy(buyQuantity = value, buyErrors = it.buyErrors - MedicationField.STOCK_QUANTITY)
        }

    /**
     * Records the purchase as one refill. A viewer, an unparsable quantity or an
     * out-of-range one never reaches the repository; the domain's [stockErrors]
     * mark the input otherwise.
     */
    fun confirmBuy() {
        val state = uiState.value
        val item = state.buy ?: return
        if (!state.canWrite || state.recording) return

        val quantity = parseStockQuantity(state.buyQuantity)
        val errors = stockErrors(quantity, StockMovementKind.REFILL)
        if (quantity == null || errors.isNotEmpty()) {
            local.update { it.copy(buyErrors = errors) }
            return
        }

        local.update { it.copy(recording = true, error = null, buyErrors = emptySet()) }
        viewModelScope.launch {
            when (val result = stockRepository.recordRefill(item.medication.id, quantity)) {
                is MedsResult.Success<*> -> local.update {
                    it.copy(recording = false, buy = null, buyQuantity = "")
                }

                is MedsResult.Failure -> local.update {
                    it.copy(
                        recording = false,
                        buyErrors = (result.error as? MedsError.Invalid)?.fields.orEmpty(),
                        error = result.uiError(),
                    )
                }
            }
        }
    }

    // --- New prescription ----------------------------------------------------

    /** Opens the date picker for [medicationId]'s new prescription; writers only. */
    fun requestPrescription(medicationId: String) {
        if (!uiState.value.canWrite) return
        val item = item(medicationId) ?: return
        local.update { it.copy(prescription = item, error = null) }
    }

    fun dismissPrescription() = local.update { it.copy(prescription = null) }

    /**
     * Stores [validUntil] as the medication's prescription's last valid day. The
     * domain treats a past date as "expired", not as a validation error, so the
     * date is written as chosen.
     */
    fun confirmPrescription(validUntil: LocalDate) {
        val state = uiState.value
        val snapshot = state.prescription ?: return
        if (!state.canWrite || state.savingPrescription) return
        // Prefer the current row: the snapshot may predate a dose or a recount.
        val item = item(snapshot.medicationId) ?: snapshot

        local.update { it.copy(savingPrescription = true, error = null) }
        viewModelScope.launch {
            val updated = item.medication.copy(prescriptionValidUntil = validUntil)
            when (val result = medicationRepository.update(updated)) {
                is MedsResult.Success<*> -> local.update {
                    it.copy(savingPrescription = false, prescription = null)
                }

                is MedsResult.Failure -> local.update {
                    it.copy(savingPrescription = false, error = result.uiError())
                }
            }
        }
    }

    fun clearError() = local.update { it.copy(error = null) }

    /** The low overview to [ShoppingItem], or null when it is not on the list. */
    private fun shoppingItem(overview: StockOverview): ShoppingItem? {
        val status = overview.summary.shoppingStatus ?: return null
        if (!overview.low) return null
        return ShoppingItem(
            medication = overview.medication,
            patientName = overview.patientName,
            daysRemaining = overview.summary.daysRemaining,
            displayStock = overview.summary.displayStock,
            status = status,
        )
    }

    private fun item(medicationId: String): ShoppingItem? =
        (uiState.value.needsPrescription + uiState.value.readyToBuy)
            .firstOrNull { it.medicationId == medicationId }
}

/** What the shopping list shows, plus the two dialogs it can open. */
data class ShoppingUiState(
    val loaded: Boolean = false,
    val canWrite: Boolean = false,
    /** The "needs a new prescription first" group, sorted by days left then name. */
    val needsPrescription: List<ShoppingItem> = emptyList(),
    /** The "ready to buy" group, in the same order. */
    val readyToBuy: List<ShoppingItem> = emptyList(),
    /** The line whose "how many did you buy?" dialog is open. */
    val buy: ShoppingItem? = null,
    /** The quantity typed in that dialog, kept as text while it is typed. */
    val buyQuantity: String = "",
    /** The quantity the domain rejected. */
    val buyErrors: Set<MedicationField> = emptySet(),
    val recording: Boolean = false,
    /** The line whose new-prescription date picker is open. */
    val prescription: ShoppingItem? = null,
    val savingPrescription: Boolean = false,
    val error: MedsUiError? = null,
) {

    /** Whether the list has loaded and has nothing on it. */
    val isEmpty: Boolean
        get() = loaded && needsPrescription.isEmpty() && readyToBuy.isEmpty()
}
