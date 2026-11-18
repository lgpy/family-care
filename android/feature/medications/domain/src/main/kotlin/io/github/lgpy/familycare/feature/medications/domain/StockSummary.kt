package io.github.lgpy.familycare.feature.medications.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Where a low-stock medication is on the shopping list (docs/ux/screens.md §5). */
enum class ShoppingStatus {
    /** A prescription is required and missing or expired: request it first. */
    NEEDS_PRESCRIPTION,

    /** Buy it: no prescription needed, or the current one is still valid. */
    READY_TO_BUY,
}

/**
 * Everything the stock section, the Today card and the shopping list show for
 * one medication, all derived from the ledger, the dose log and the schedule
 * (D28).
 *
 * [stock] is not clamped: doses recorded before any count make it negative, and
 * the screen shows `max(stock, 0)` ([displayStock]). [daysRemaining] is null
 * when no regimen is active today (nothing is being consumed), 0 when there is
 * no stock left, and otherwise the floored number of days the stock covers.
 */
data class StockSummary(
    val stock: BigDecimal,
    val dailyConsumption: BigDecimal,
    val daysRemaining: Int?,
    val low: Boolean,
    val shoppingStatus: ShoppingStatus?,
) {

    /** What the UI may show: stock never looks negative to a caregiver. */
    val displayStock: BigDecimal get() = if (stock.signum() < 0) BigDecimal.ZERO else stock

    companion object {
        /** The summary of a medication with no ledger and no active schedule. */
        val EMPTY: StockSummary = StockSummary(
            stock = BigDecimal.ZERO,
            dailyConsumption = BigDecimal.ZERO,
            daysRemaining = null,
            low = false,
            shoppingStatus = null,
        )
    }
}

/**
 * The stock rules from docs/domain/medications.md "Stock" and
 * `shared/test-vectors/README.md` (`stock.json`, D28).
 *
 * ### The ledger
 * - **Baseline:** the `count` with the latest `occurred_at`; ties go to the
 *   greater id. Without any count the baseline is 0 at the beginning of time.
 * - **Stock** = baseline + refills − discards whose `occurred_at` is **after**
 *   the baseline − the effective `taken` dose quantities whose `actual_at` is
 *   after the baseline. Anything at or before the baseline is already reflected
 *   in the count.
 * - **Effective entries** reuse [DoseLogRules]: an entry named by another
 *   entry's `corrects_entry_id` is void, so a correction deducts instead of the
 *   entry it replaces, and two independent confirmations deduct twice.
 * - A `taken` entry with no `actual_quantity` deducts the quantity of its
 *   regimen time (the `times[]` entry with the same wall-clock time), or 0 when
 *   the time is not found.
 * - Stock is **not clamped**; the UI shows `max(stock, 0)`.
 *
 * ### Days remaining
 * Consumption and days are computed with [BigDecimal] and **multiply before
 * dividing** (`18 * 7 / 3` is exactly 42, whereas `18 / (3/7)` in floating point
 * can floor to 41).
 */
object StockRules {

    /** Mirrors `meds.stock_movements_quantity_max` (the server's numeric(9,3)). */
    val MAX_QUANTITY: BigDecimal = BigDecimal("100000")

    /** Digits after the decimal point, mirroring `numeric(9,3)`. */
    const val MAX_DECIMAL_PLACES: Int = 3

    /** Mirrors `meds.stock_movements_reason_length`. */
    const val MAX_REASON_LENGTH: Int = 500

    /** Decimal places kept for an average consumption (`Q × days / 7`, `Q / N`). */
    private const val CONSUMPTION_SCALE: Int = 18

    // --- Validation ---------------------------------------------------------

    /**
     * The intrinsic rules of one movement, mirroring the server's check
     * constraints: a count is ≥ 0, a refill or discard > 0, every quantity is
     * ≤ [MAX_QUANTITY] with at most three decimals, and a discard says why.
     */
    fun validate(movement: StockMovement): MedicationValidation {
        val errors = mutableSetOf<MedicationField>()

        val quantity = movement.quantity
        val quantityAllowed = quantity <= MAX_QUANTITY &&
            QuantityRules.decimalPlaces(quantity) <= MAX_DECIMAL_PLACES
        val quantityInRange = when (movement.kind) {
            StockMovementKind.COUNT -> quantity.signum() >= 0
            StockMovementKind.REFILL, StockMovementKind.DISCARD -> quantity.signum() > 0
        }
        if (!quantityAllowed || !quantityInRange) {
            errors += MedicationField.STOCK_QUANTITY
        }

        val reason = MedicationRules.normalizeOptional(movement.reason)
        if (movement.kind == StockMovementKind.DISCARD && reason == null) {
            errors += MedicationField.STOCK_REASON
        }
        if ((reason?.length ?: 0) > MAX_REASON_LENGTH) {
            errors += MedicationField.STOCK_REASON
        }

        return MedicationValidation(errors)
    }

    // --- Current stock ------------------------------------------------------

    /**
     * The current stock of one medication (not clamped).
     *
     * @param movements That medication's ledger, in any order.
     * @param entries That medication's dose-log entries, in any order.
     * @param regimens Every version of the medication's regimen, so a taken
     *   entry without `actual_quantity` can deduct its slot's quantity.
     */
    fun currentStock(
        movements: List<StockMovement>,
        entries: List<DoseLogEntry>,
        regimens: List<Regimen>,
    ): BigDecimal {
        val baseline = baselineOf(movements)
        val baselineAt = baseline?.occurredAt
        var stock = baseline?.quantity ?: BigDecimal.ZERO

        movements.forEach { movement ->
            if (movement.kind == StockMovementKind.COUNT) return@forEach
            if (baselineAt != null && !movement.occurredAt.isAfter(baselineAt)) return@forEach
            stock = when (movement.kind) {
                StockMovementKind.REFILL -> stock + movement.quantity
                StockMovementKind.DISCARD -> stock - movement.quantity
                StockMovementKind.COUNT -> stock
            }
        }

        val consumed = effectiveTaken(entries)
            .filter { baselineAt == null || it.actualAt.isAfter(baselineAt) }
            .fold(BigDecimal.ZERO) { total, entry ->
                total + (entry.actualQuantity ?: slotQuantity(entry, regimens))
            }

        return stock - consumed
    }

    /** The count that resets the baseline: latest `occurred_at`, ties by greater id. */
    fun baselineOf(movements: List<StockMovement>): StockMovement? =
        movements
            .filter { it.kind == StockMovementKind.COUNT }
            .sortedWith(compareBy({ it.occurredAt }, { it.id.value }))
            .lastOrNull()

    /**
     * The non-void `taken` entries, oldest first.
     *
     * Corrections are resolved by [DoseLogRules.effectiveEntries], so this never
     * re-implements the voiding rule: a corrected entry is not counted twice.
     */
    fun effectiveTaken(entries: List<DoseLogEntry>): List<DoseLogEntry> =
        DoseLogRules.effectiveEntries(entries).filter { it.outcome == DoseOutcome.TAKEN }

    /**
     * The quantity a taken entry with no `actual_quantity` used: the regimen
     * time whose wall-clock time equals the entry's `scheduled_local`, or 0 when
     * the regimen (or that time) is not found.
     */
    fun slotQuantity(entry: DoseLogEntry, regimens: List<Regimen>): BigDecimal {
        val regimen = regimens.firstOrNull { it.id == entry.regimenId } ?: return BigDecimal.ZERO
        val scheduled = entry.scheduledLocal
        return regimen.times
            .firstOrNull {
                it.time.hour == scheduled.hour && it.time.minute == scheduled.minute
            }
            ?.quantity
            ?: BigDecimal.ZERO
    }

    // --- Schedule -----------------------------------------------------------

    /**
     * The version that applies on [today]: `start_date ≤ today ≤ end_date` (or
     * no end date). The latest [Regimen.startDate] wins when several do; ties
     * are broken by id so the result is deterministic.
     *
     * The schedule's own weekday/interval selection does not matter here: the
     * daily consumption is an average over the week, so a weekdays regimen that
     * does not occur today still consumes `Q × days / 7` per day.
     */
    fun activeRegimen(regimens: List<Regimen>, today: LocalDate): Regimen? =
        regimens
            .filter { regimen ->
                !today.isBefore(regimen.startDate) &&
                    (regimen.endDate == null || !today.isAfter(regimen.endDate))
            }
            .maxWithOrNull(compareBy({ it.startDate }, { it.id.value }))

    /**
     * How much the [regimen] takes out of stock per day on average:
     * `Q` for daily, `Q × popcount(weekdays) / 7` for weekdays and
     * `Q / interval_days` for every N days. Null (or no times) is 0.
     */
    fun dailyConsumption(regimen: Regimen?): BigDecimal {
        if (regimen == null) return BigDecimal.ZERO
        val perOccurrence = regimen.times.fold(BigDecimal.ZERO) { total, time ->
            total + time.quantity
        }

        return when (regimen.scheduleType) {
            ScheduleType.DAILY -> perOccurrence
            ScheduleType.WEEKDAYS -> {
                val days = regimen.weekdays?.let { Integer.bitCount(it) } ?: 0
                if (days == 0) {
                    BigDecimal.ZERO
                } else {
                    perOccurrence.multiply(BigDecimal(days))
                        .divide(BigDecimal(7), CONSUMPTION_SCALE, RoundingMode.HALF_UP)
                }
            }

            ScheduleType.EVERY_N_DAYS -> {
                val interval = regimen.intervalDays ?: 0
                if (interval <= 0) {
                    BigDecimal.ZERO
                } else {
                    perOccurrence.divide(
                        BigDecimal(interval),
                        CONSUMPTION_SCALE,
                        RoundingMode.HALF_UP,
                    )
                }
            }
        }
    }

    /**
     * Days of stock left: null when nothing is consumed, 0 when [stock] is ≤ 0,
     * otherwise `floor(stock × P / (Q × K))` with the period `P` and the
     * occurrences per period `K` (daily: 1/1; weekdays: 7/popcount; every N
     * days: N/1).
     */
    fun daysRemaining(stock: BigDecimal, regimen: Regimen?): Int? {
        if (regimen == null) return null
        if (dailyConsumption(regimen).signum() == 0) return null
        if (stock.signum() <= 0) return 0

        val period = when (regimen.scheduleType) {
            ScheduleType.DAILY -> 1
            ScheduleType.WEEKDAYS -> 7
            ScheduleType.EVERY_N_DAYS -> regimen.intervalDays ?: return null
        }
        val occurrences = when (regimen.scheduleType) {
            ScheduleType.DAILY -> 1
            ScheduleType.WEEKDAYS -> regimen.weekdays?.let { Integer.bitCount(it) } ?: return null
            ScheduleType.EVERY_N_DAYS -> 1
        }
        if (occurrences <= 0 || period <= 0) return null

        val perOccurrence = regimen.times.fold(BigDecimal.ZERO) { total, time ->
            total + time.quantity
        }
        val days = stock.multiply(BigDecimal(period))
            .divide(perOccurrence.multiply(BigDecimal(occurrences)), 0, RoundingMode.FLOOR)

        return if (days > BigDecimal(Int.MAX_VALUE)) Int.MAX_VALUE else days.toInt()
    }

    /** Low when there is a number of days left and it is at most [thresholdDays]. */
    fun isLow(daysRemaining: Int?, thresholdDays: Int): Boolean =
        daysRemaining != null && daysRemaining <= thresholdDays

    /**
     * The shopping-list state: only a low medication is on the list, and it
     * needs a prescription first when one is required and the validity date is
     * missing or before [today] (valid through the date itself).
     */
    fun shoppingStatus(
        low: Boolean,
        requiresPrescription: Boolean,
        prescriptionValidUntil: LocalDate?,
        today: LocalDate,
    ): ShoppingStatus? {
        if (!low) return null
        val needsOne = requiresPrescription &&
            (prescriptionValidUntil == null || prescriptionValidUntil.isBefore(today))
        return if (needsOne) ShoppingStatus.NEEDS_PRESCRIPTION else ShoppingStatus.READY_TO_BUY
    }

    // --- One call for a screen ---------------------------------------------

    /**
     * The summary of [medication] on [today], derived from its ledger, its
     * dose log and its regimen versions.
     *
     * [movements] and [entries] may hold other medications' rows; they are
     * filtered here so a caller cannot mix two ledgers by accident.
     */
    fun summary(
        medication: Medication,
        regimens: List<Regimen>,
        movements: List<StockMovement>,
        entries: List<DoseLogEntry>,
        today: LocalDate,
    ): StockSummary {
        val ownMovements = movements.filter { it.medicationId == medication.id }
        val ownEntries = entries.filter { it.medicationId == medication.id }

        val stock = currentStock(ownMovements, ownEntries, regimens)
        val regimen = activeRegimen(regimens, today)
        val consumption = dailyConsumption(regimen)
        val days = daysRemaining(stock, regimen)
        val low = isLow(days, medication.lowStockThresholdDays)

        return StockSummary(
            stock = stock,
            dailyConsumption = consumption,
            daysRemaining = days,
            low = low,
            shoppingStatus = shoppingStatus(
                low = low,
                requiresPrescription = medication.requiresPrescription,
                prescriptionValidUntil = medication.prescriptionValidUntil,
                today = today,
            ),
        )
    }
}
