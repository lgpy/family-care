package io.github.lgpy.familycare.feature.medications.ui

import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.StockMovement
import io.github.lgpy.familycare.feature.medications.domain.StockMovementId
import io.github.lgpy.familycare.feature.medications.domain.StockMovementKind
import java.math.BigDecimal
import java.time.Instant

/**
 * The stock ledger's inputs, shared by the add form's opening count and the
 * detail screen's dialogs (D28).
 *
 * A quantity is kept as text while it is typed and parsed once, so a half-typed
 * `0.` is never silently turned into 0. The rules themselves are the domain's
 * ([io.github.lgpy.familycare.feature.medications.domain.StockRules.validate]):
 * a count is ≥ 0, a refill or discard > 0, at most 100000 with at most three
 * decimals, and a discard says why.
 */

/**
 * Parses a typed quantity, or null when it is not a number.
 *
 * A pt-PT keyboard offers `,` as the decimal separator, so both `,` and `.` are
 * accepted; the value keeps the digits the caregiver typed.
 */
fun parseStockQuantity(value: String): BigDecimal? =
    value.trim().replace(',', '.').toBigDecimalOrNull()

/** The number of days a threshold text holds, or null when it is not a number. */
fun parseThresholdDays(value: String): Int? = value.trim().toIntOrNull()

/**
 * The [MedicationField]s the domain rejects for a movement of [kind] with
 * [quantity] and [reason], so a dialog marks the input that is wrong instead of
 * repeating the rules.
 *
 * A missing or unparseable quantity is [MedicationField.STOCK_QUANTITY].
 */
fun stockErrors(
    quantity: BigDecimal?,
    kind: StockMovementKind,
    reason: String? = null,
): Set<MedicationField> {
    if (quantity == null) return setOf(MedicationField.STOCK_QUANTITY)
    val movement = StockMovement(
        id = StockMovementId("draft"),
        medicationId = MedicationId("draft"),
        kind = kind,
        quantity = quantity,
        occurredAt = Instant.EPOCH,
        reason = reason,
    )
    return movement.validate().errors
}
