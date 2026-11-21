package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.feature.medications.data.StockOverview
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One medication line of the "Low stock" card (M5; D28).
 *
 * The values come from [StockOverview], so the card adds nothing up itself: the
 * low flag and the number of days left are the domain's summary.
 */
data class LowStockLine(
    val medicationId: String,
    val patientId: String,
    val patientName: String,
    val medicationName: String,
    /** The box's strength ("850 mg"), shown after the name when known. */
    val strength: String?,
    val daysRemaining: Int,
    /** Whether a prescription must be sorted out before buying. */
    val needsPrescription: Boolean,
)

/**
 * Every low medication of the circle, fewest days left first.
 *
 * The attention card draws one row for a single medication and one summary row
 * for several, so the list is not capped here. An empty list means nothing is
 * shown — nothing is low, or the ledger has not emitted yet ([loaded] tells the
 * two apart, as on the shopping list).
 */
data class LowStockUiState(
    val loaded: Boolean = false,
    val lines: List<LowStockLine> = emptyList(),
) {

    /** Whether the card has anything to show. */
    val isVisible: Boolean get() = lines.isNotEmpty()
}

/**
 * The low-stock rows of Today's single attention card (M5; D28).
 *
 * It watches [StockRepository.overviews] — every active medication of the circle
 * with its derived stock summary — and keeps the low ones. The list re-emits on
 * every dose, refill or recount, so a medication disappears from the card as
 * soon as it is no longer low; there is no dismissal state to keep in step.
 * A medication whose stock was never recorded is not low ([StockOverview.low]).
 */
class LowStockViewModel(stockRepository: StockRepository) : ViewModel() {

    val uiState: StateFlow<LowStockUiState> = stockRepository.overviews
        .map(::toUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LowStockUiState(),
        )
}

/** The low overviews, most urgent first. */
private fun toUiState(overviews: List<StockOverview>): LowStockUiState {
    val lines = overviews
        .asSequence()
        .filter { it.low }
        // A low medication always has a number of days left (the domain's
        // isLow() requires one); the null case is dropped rather than invented.
        .mapNotNull { overview ->
            val days = overview.summary.daysRemaining ?: return@mapNotNull null
            LowStockLine(
                medicationId = overview.medication.id.value,
                patientId = overview.medication.patientId.value,
                patientName = overview.patientName,
                medicationName = overview.medication.name,
                strength = overview.medication.strength,
                daysRemaining = days,
                needsPrescription = overview.needsPrescription,
            )
        }
        .sortedWith(
            compareBy(
                { it.daysRemaining },
                { it.medicationName.lowercase() },
                { it.patientName.lowercase() },
            ),
        )
        .toList()

    return LowStockUiState(loaded = true, lines = lines)
}
