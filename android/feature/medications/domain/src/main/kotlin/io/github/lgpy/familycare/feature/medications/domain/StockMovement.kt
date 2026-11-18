package io.github.lgpy.familycare.feature.medications.domain

import java.math.BigDecimal
import java.time.Instant

/**
 * Identity of one append-only `meds.stock_movements` row (D6/D28).
 *
 * Generated on the phone, so a count can be recorded in a pharmacy's basement
 * with no network and uploaded later.
 */
@JvmInline
value class StockMovementId(val value: String) {
    init {
        require(value.isNotBlank()) { "A stock movement id must not be blank" }
    }
}

/**
 * What a stock movement does (D28, docs/domain/medications.md "Stock").
 *
 * There is deliberately no "dose" kind: what a dose took out of the box is
 * derived from the dose log (D6), never written twice.
 */
enum class StockMovementKind(val serverValue: String) {
    /** A stocktake: "there are N now". Also the opening balance. May be 0. */
    COUNT("count"),

    /** Stock that came in (a purchase). Strictly positive. */
    REFILL("refill"),

    /** Stock thrown away (expired, lost). Strictly positive, with a reason. */
    DISCARD("discard"),
    ;

    companion object {
        /** The kind with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): StockMovementKind? =
            entries.firstOrNull { it.serverValue == value }
    }
}

/**
 * One append-only entry of a medication's stock ledger (D28).
 *
 * Current stock is derived, never stored: the latest [StockMovementKind.COUNT]
 * is the baseline, refills add to it, discards subtract, and the effective
 * `taken` entries of the dose log subtract what the doses used. See
 * [StockRules] and `shared/test-vectors/README.md` (`stock.json`).
 *
 * @param quantity In the medication's `stock_unit`. A count may be 0, a refill
 *   or a discard must be greater than 0; at most 3 decimals and at most
 *   [StockRules.MAX_QUANTITY] (the server's `numeric(9,3)`).
 * @param reason Why the stock was discarded; required for
 *   [StockMovementKind.DISCARD], optional otherwise.
 * @param occurredAt When it happened. A count resets the baseline; a movement
 *   at or before the latest count is already reflected in that count.
 */
data class StockMovement(
    val id: StockMovementId,
    val medicationId: MedicationId,
    val kind: StockMovementKind,
    val quantity: BigDecimal,
    val occurredAt: Instant,
    val reason: String? = null,
) {

    /** The same movement with its reason trimmed (blank → null) and quantity normalized. */
    fun normalized(): StockMovement = copy(
        quantity = QuantityRules.normalize(quantity),
        reason = MedicationRules.normalizeOptional(reason),
    )

    /** Intrinsic validity, mirroring the `meds.stock_movements` check constraints. */
    fun validate(): MedicationValidation = StockRules.validate(this)
}
