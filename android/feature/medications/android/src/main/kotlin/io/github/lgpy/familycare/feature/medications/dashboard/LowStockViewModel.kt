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
 * What the "Low stock" card shows: the most urgent low medications and how many
 * did not fit.
 *
 * [lines] is already capped at [LowStockViewModel.MAX_LINES] and ordered by days
 * left; [more] is the rest, drawn as "+N more". An empty list means the card is
 * hidden — nothing is low, or the ledger has not emitted yet ([loaded] tells the
 * two apart, as on the shopping list).
 */
data class LowStockUiState(
    val loaded: Boolean = false,
    val lines: List<LowStockLine> = emptyList(),
    val more: Int = 0,
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
 *
 * The card is a digest, not the list: the five with the fewest days left, then
 * "+N more", and tapping it opens the Shopping tab for the full list.
 */
class LowStockViewModel(stockRepository: StockRepository) : ViewModel() {

    val uiState: StateFlow<LowStockUiState> = stockRepository.overviews
        .map(::toUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LowStockUiState(),
        )

    companion object {

        /** How many medications the card lists before "+N more". */
        const val MAX_LINES: Int = 5
    }
}

/** The low overviews, most urgent first, capped for the card. */
private fun toUiState(overviews: List<StockOverview>): LowStockUiState {
    val lines = overviews
        .asSequence()
        .filter { it.summary.low }
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

    return LowStockUiState(
        loaded = true,
        lines = lines.take(LowStockViewModel.MAX_LINES),
        more = (lines.size - LowStockViewModel.MAX_LINES).coerceAtLeast(0),
    )
}
