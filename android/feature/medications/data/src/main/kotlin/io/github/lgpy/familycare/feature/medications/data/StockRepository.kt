package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.ShoppingStatus
import io.github.lgpy.familycare.feature.medications.domain.StockMovement
import io.github.lgpy.familycare.feature.medications.domain.StockMovementId
import io.github.lgpy.familycare.feature.medications.domain.StockSummary
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

/**
 * One medication with its stock ledger and the summary derived from it (D28).
 *
 * [summary] is computed by the domain ([io.github.lgpy.familycare.feature.medications.domain.StockRules])
 * from the medication, its regimen versions, its movements and its dose log —
 * the screens never add up a ledger themselves.
 */
data class StockOverview(
    val item: MedicationWithRegimens,
    val movements: List<StockMovement>,
    val summary: StockSummary,
) {

    /** The medication this overview is for. */
    val medication: Medication get() = item.medication

    /** The patient's name, so a shopping line needs no second query. */
    val patientName: String get() = item.patient.displayName

    /**
     * Whether anyone has ever recorded stock for this medication (a count, a
     * refill or a discard). The domain starts an uncounted ledger at 0, which
     * reads as "0 days left"; the app does not know the box is empty, it only
     * was never told, so an untracked medication is never low.
     */
    val tracked: Boolean get() = movements.isNotEmpty()

    /** Whether the medication is low enough to be on the shopping list. */
    val low: Boolean get() = tracked && summary.low

    /** Whether the shopping list must ask for a prescription first. */
    val needsPrescription: Boolean
        get() = low && summary.shoppingStatus == ShoppingStatus.NEEDS_PRESCRIPTION
}

/**
 * The stock ledger (D28) and the shopping list derived from it.
 *
 * Every write is one local INSERT in one write transaction, so it appears
 * immediately and works offline; PowerSync uploads it later. Nothing is ever
 * updated or deleted: a mistake is corrected by recording a count ("there are N
 * now"), which is what a caregiver would do with a box in hand.
 *
 * Reads are watches over the local database and re-emit on every local or
 * synced change: confirming a dose changes the stock immediately, without a
 * second ledger to keep in step, because doses are deducted from the dose log.
 *
 * The shopping list is **derived**, never stored: a low medication of a
 * non-stopped treatment, split into "needs a prescription first" and "ready to
 * buy" by [io.github.lgpy.familycare.feature.medications.domain.StockRules.shoppingStatus].
 */
interface StockRepository {

    /** The ledger, summary and medication of one medication; null while unknown. */
    fun observeOverview(medicationId: MedicationId): Flow<StockOverview?>

    /** One medication's movements, oldest first (the detail screen's history). */
    fun observeMovements(medicationId: MedicationId): Flow<List<StockMovement>>

    /**
     * Every active medication of the current circle with its stock summary.
     * The Today card and the shopping screen both filter this for
     * [StockOverview.low]; a stopped medication never appears.
     */
    val overviews: Flow<List<StockOverview>>

    /**
     * Records a stocktake: "there are [quantity] now" (0 or more). This is also
     * how a mistake is fixed and how stock is reconciled after a correction.
     *
     * @param note Optional free text, e.g. "opened a new box".
     * @return the new movement's id, or [MedsError.Invalid] with
     *   [io.github.lgpy.familycare.feature.medications.domain.MedicationField.STOCK_QUANTITY].
     */
    suspend fun recordCount(
        medicationId: MedicationId,
        quantity: BigDecimal,
        note: String? = null,
    ): MedsResult<StockMovementId>

    /** Records stock that came in: a purchase, greater than 0. */
    suspend fun recordRefill(
        medicationId: MedicationId,
        quantity: BigDecimal,
    ): MedsResult<StockMovementId>

    /** Records stock thrown away (greater than 0) with the reason it was. */
    suspend fun recordDiscard(
        medicationId: MedicationId,
        quantity: BigDecimal,
        reason: String,
    ): MedsResult<StockMovementId>
}
