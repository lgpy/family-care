package io.github.lgpy.familycare.feature.medications.domain

import java.math.BigDecimal
import java.time.LocalTime

/**
 * How a dose quantity is represented and checked (docs/domain/medications.md
 * "Quantity per time slot, which can differ… Fractions allowed").
 *
 * A quantity is a [BigDecimal], so `½` is `0.5` and `1½` is `1.5` without the
 * rounding of a binary floating-point type. The database column is
 * `numeric(6,3)`; the shared vectors encode it as a plain JSON number with at
 * most three decimal places, e.g. `1`, `0.5`, `0.125` (never a string and never
 * an exponent form).
 *
 * Comparison is numeric: `1.0` and `1` are the same quantity even though the
 * BigDecimals differ in scale. [normalize] strips trailing zeros so a value can
 * be compared or logged without cosmetic noise.
 */
object QuantityRules {

    /** The smallest allowed quantity; the rule is strictly greater than it. */
    val MIN_EXCLUSIVE: BigDecimal = BigDecimal.ZERO

    /** The largest allowed single-dose quantity. */
    val MAX: BigDecimal = BigDecimal("100")

    /** Digits after the decimal point, mirroring `numeric(6,3)`. */
    const val MAX_DECIMAL_PLACES: Int = 3

    /** Significant decimal places, ignoring trailing zeros (`1.500` → 0). */
    fun decimalPlaces(quantity: BigDecimal): Int =
        maxOf(0, quantity.stripTrailingZeros().scale())

    /** Whether [quantity] is a valid dose: > 0, ≤ 100, at most 3 decimals. */
    fun isValid(quantity: BigDecimal): Boolean =
        quantity > MIN_EXCLUSIVE &&
            quantity <= MAX &&
            decimalPlaces(quantity) <= MAX_DECIMAL_PLACES

    /** The same quantity without trailing zeros; never changes the value. */
    fun normalize(quantity: BigDecimal): BigDecimal =
        quantity.stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        }

    /**
     * The plain decimal form used in vectors, logs and error reports
     * (`0.5`, `1`, `0.125`). Never scientific notation.
     */
    fun format(quantity: BigDecimal): String =
        normalize(quantity).toPlainString()
}

/**
 * One fixed time of a regimen and the quantity taken then.
 *
 * Times are local wall-clock times in the regimen's zone and only carry minute
 * precision (the UI picks `HH:MM`); seconds and nanos must be zero, which
 * [RegimenRules.validate] enforces. Fractions are allowed and independent per
 * time (`1` at 08:00, `0.5` at 21:00).
 */
data class DoseTime(
    val time: LocalTime,
    val quantity: BigDecimal,
) {

    /** Whether the time is on a whole minute. */
    val isOnMinute: Boolean get() = time.second == 0 && time.nano == 0

    /** Whether this time and quantity can be stored (see [QuantityRules]). */
    val isValid: Boolean get() = isOnMinute && QuantityRules.isValid(quantity)

    /** The same entry with the quantity in normal form. */
    fun normalized(): DoseTime = copy(quantity = QuantityRules.normalize(quantity))

    /** The quantity as vectors and reports write it (`0.5`, `1`). */
    val formattedQuantity: String get() = QuantityRules.format(quantity)
}
