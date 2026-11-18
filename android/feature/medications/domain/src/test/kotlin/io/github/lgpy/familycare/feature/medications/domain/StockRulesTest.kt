package io.github.lgpy.familycare.feature.medications.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The stock rules the vectors cannot pin down on their own (D28). */
class StockRulesTest {

    private val medicationId = MedicationId("med-1")
    private val today = LocalDate.of(2026, 11, 18)

    private fun movement(
        kind: StockMovementKind,
        quantity: String,
        reason: String? = null,
        at: String = "2026-11-17T10:00:00Z",
        id: String = "mv1",
    ) = StockMovement(
        id = StockMovementId(id),
        medicationId = medicationId,
        kind = kind,
        quantity = BigDecimal(quantity),
        occurredAt = Instant.parse(at),
        reason = reason,
    )

    private fun daily(quantity: String = "1") = Regimen(
        id = RegimenId("reg-1"),
        medicationId = medicationId,
        scheduleType = ScheduleType.DAILY,
        startDate = LocalDate.of(2026, 11, 1),
        timeZone = ZoneId.of("Europe/Lisbon"),
        times = listOf(DoseTime(LocalTime.of(8, 0), BigDecimal(quantity))),
    )

    // --- Validation ---------------------------------------------------------

    @Test
    fun `a count may be zero`() {
        assertTrue(movement(StockMovementKind.COUNT, "0").validate().isValid)
    }

    @Test
    fun `a negative count is refused`() {
        assertEquals(
            setOf(MedicationField.STOCK_QUANTITY),
            movement(StockMovementKind.COUNT, "-1").validate().errors,
        )
    }

    @Test
    fun `a refill and a discard must be greater than zero`() {
        listOf(StockMovementKind.REFILL, StockMovementKind.DISCARD).forEach { kind ->
            assertEquals(
                setOf(MedicationField.STOCK_QUANTITY),
                movement(kind, "0", reason = "expired").validate().errors,
                "$kind of zero",
            )
        }
    }

    @Test
    fun `the quantity is capped and has at most three decimals`() {
        assertTrue(movement(StockMovementKind.REFILL, "100000").validate().isValid)
        assertEquals(
            setOf(MedicationField.STOCK_QUANTITY),
            movement(StockMovementKind.REFILL, "100000.001").validate().errors,
        )
        assertEquals(
            setOf(MedicationField.STOCK_QUANTITY),
            movement(StockMovementKind.REFILL, "0.1234").validate().errors,
        )
    }

    @Test
    fun `a discard needs a reason of at most 500 characters`() {
        assertEquals(
            setOf(MedicationField.STOCK_REASON),
            movement(StockMovementKind.DISCARD, "2").validate().errors,
        )
        assertEquals(
            setOf(MedicationField.STOCK_REASON),
            movement(StockMovementKind.DISCARD, "2", reason = "  ").validate().errors,
        )
        assertTrue(movement(StockMovementKind.DISCARD, "2", reason = "expired").validate().isValid)

        val long = movement(StockMovementKind.DISCARD, "2", reason = "x".repeat(501))
        assertEquals(setOf(MedicationField.STOCK_REASON), long.validate().errors)
    }

    @Test
    fun `a refill may carry a reason and a count does not need one`() {
        assertTrue(movement(StockMovementKind.REFILL, "30", reason = "pharmacy").validate().isValid)
        assertTrue(movement(StockMovementKind.COUNT, "12").validate().isValid)
    }

    @Test
    fun `a movement is normalized before it is stored`() {
        val normalized = movement(StockMovementKind.DISCARD, "2.500", reason = "  expired ")
            .normalized()

        assertEquals("2.5", QuantityRules.format(normalized.quantity))
        assertEquals("expired", normalized.reason)
    }

    // --- Derivation details -------------------------------------------------

    @Test
    fun `without a count the baseline is zero and every dose counts`() {
        val entry = DoseLogEntry(
            id = DoseLogEntryId("d1"),
            regimenId = RegimenId("reg-1"),
            medicationId = medicationId,
            scheduledLocal = today.atTime(8, 0),
            outcome = DoseOutcome.TAKEN,
            actualAt = Instant.parse("2026-11-16T08:00:00Z"),
            confirmedBy = "u1",
            actualQuantity = BigDecimal.ONE,
        )

        assertEquals(
            BigDecimal("-1"),
            StockRules.currentStock(emptyList(), listOf(entry), listOf(daily())),
        )
    }

    @Test
    fun `a count at zero is still a baseline`() {
        val count = movement(StockMovementKind.COUNT, "0", at = "2026-11-17T10:00:00Z")
        val olderRefill = movement(StockMovementKind.REFILL, "5", at = "2026-11-16T10:00:00Z", id = "mv2")

        assertEquals(
            BigDecimal.ZERO,
            StockRules.currentStock(listOf(olderRefill, count), emptyList(), listOf(daily())),
        )
    }

    @Test
    fun `two counts at the same instant go to the greater id`() {
        val first = movement(StockMovementKind.COUNT, "5", id = "mv-a")
        val second = movement(StockMovementKind.COUNT, "9", id = "mv-b")

        assertEquals(
            BigDecimal("9"),
            StockRules.currentStock(listOf(second, first), emptyList(), listOf(daily())),
        )
    }

    @Test
    fun `weekdays days remaining multiplies before dividing`() {
        val weekdays = Regimen(
            id = RegimenId("reg-1"),
            medicationId = medicationId,
            scheduleType = ScheduleType.WEEKDAYS,
            startDate = LocalDate.of(2026, 11, 1),
            timeZone = ZoneId.of("Europe/Lisbon"),
            times = listOf(DoseTime(LocalTime.of(9, 0), BigDecimal.ONE)),
            weekdays = WeekdayMask.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY),
        )

        assertEquals(BigDecimal("0.428571428571428571"), StockRules.dailyConsumption(weekdays))
        assertEquals(42, StockRules.daysRemaining(BigDecimal("18"), weekdays))
    }

    @Test
    fun `an inactive regimen has no consumption and no days`() {
        val ended = daily().copy(endDate = LocalDate.of(2026, 11, 10))

        assertNull(StockRules.activeRegimen(listOf(ended), today))
        assertEquals(BigDecimal.ZERO, StockRules.dailyConsumption(null))
        assertNull(StockRules.daysRemaining(BigDecimal.TEN, null))
        assertFalse(StockRules.isLow(null, 7))
    }

    @Test
    fun `the latest start date wins when two versions overlap`() {
        val older = daily().copy(id = RegimenId("reg-1"), startDate = LocalDate.of(2026, 11, 1))
        val newer = daily("2").copy(id = RegimenId("reg-2"), startDate = LocalDate.of(2026, 11, 15))

        assertEquals(RegimenId("reg-2"), StockRules.activeRegimen(listOf(older, newer), today)?.id)
    }

    @Test
    fun `shopping status is null until the medication is low`() {
        assertNull(StockRules.shoppingStatus(false, true, null, today))
        assertEquals(
            ShoppingStatus.READY_TO_BUY,
            StockRules.shoppingStatus(true, false, null, today),
        )
    }
}
