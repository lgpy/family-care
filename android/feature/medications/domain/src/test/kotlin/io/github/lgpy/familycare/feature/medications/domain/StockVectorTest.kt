package io.github.lgpy.familycare.feature.medications.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The M5 stock vectors in `shared/test-vectors/stock.json` (D22/D28).
 *
 * The file is on the test classpath because the module's build puts
 * `<repo>/shared/test-vectors` on the test resources. Every expected value was
 * computed by hand from `README.md` (section `stock.json`); numbers are compared
 * with a tolerance of `1e-9` as the file says.
 */
class StockVectorTest {

    @Test
    fun `every stock vector matches the derivation`() {
        val cases = load().getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty(), "stock.json has no cases")

        cases.forEach { caseElement ->
            val case = caseElement.jsonObject
            val name = case.requiredText("name")
            val today = LocalDate.parse(case.requiredText("today"))
            val medication = medicationOf(case.getValue("medication").jsonObject)
            val regimens = case.getValue("regimens").jsonArray.map { regimenOf(it.jsonObject) }
            val movements = case.get("movements")?.jsonArray.orEmpty().map { movementOf(it.jsonObject) }
            val entries = case.get("dose_log")?.jsonArray.orEmpty().map { entryOf(it.jsonObject) }

            val actual = StockRules.summary(medication, regimens, movements, entries, today)
            val expected = case.getValue("expected").jsonObject

            assertEqualsDecimal(
                expected.decimal("stock"),
                actual.stock,
                "stock in case '$name'",
            )
            assertEqualsDecimal(
                expected.decimal("daily_consumption"),
                actual.dailyConsumption,
                "daily_consumption in case '$name'",
            )
            assertEquals(
                expected.optionalInt("days_remaining"),
                actual.daysRemaining,
                "days_remaining in case '$name'",
            )
            assertEquals(
                expected.getValue("low").jsonPrimitive.boolean,
                actual.low,
                "low in case '$name'",
            )
            assertEquals(
                expected.optionalText("shopping_status")?.uppercase(),
                actual.shoppingStatus?.name,
                "shopping_status in case '$name'",
            )
        }
    }

    @Test
    fun `the vector file pins the tricky rules`() {
        val names = load().getValue("cases").jsonArray
            .map { it.jsonObject.requiredText("name") }

        listOf(
            "recount-resets-the-baseline",
            "corrections-replace-the-voided-entry",
            "double-confirmation-deducts-twice",
            "dose-before-the-count-is-ignored",
            "no-count-goes-negative-and-is-low",
            "no-regimen-active-today-is-never-low",
            "new-regimen-version-from-today-and-null-quantity",
            "zero-threshold-low-only-at-zero-days",
        ).forEach { required ->
            assertTrue(required in names, "stock.json lost the '$required' case")
        }
    }

    // --- Reading the file ---------------------------------------------------

    private fun load(): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/stock.json")) {
            "shared vector 'stock.json' is not on the test classpath " +
                "(expected it from <repo>/shared/test-vectors)"
        }
        return stream.bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
        }
    }

    private fun medicationOf(json: JsonObject): Medication = Medication(
        id = MedicationId("m1"),
        patientId = io.github.lgpy.familycare.core.domain.circle.PatientId("p1"),
        name = "Metformin",
        form = MedicationForm.TABLET,
        stockUnit = "tablet",
        requiresPrescription = json.getValue("requires_prescription").jsonPrimitive.boolean,
        prescriptionValidUntil = json.optionalText("prescription_valid_until")?.let(LocalDate::parse),
        lowStockThresholdDays = json.getValue("low_stock_threshold_days").jsonPrimitive.int,
    )

    private fun regimenOf(json: JsonObject): Regimen = Regimen(
        id = RegimenId(json.requiredText("id")),
        medicationId = MedicationId(json.requiredText("medication_id")),
        scheduleType = checkNotNull(
            ScheduleType.fromServer(json.requiredText("schedule_type")),
        ) { "unknown schedule_type '${json.requiredText("schedule_type")}'" },
        startDate = LocalDate.parse(json.requiredText("start_date")),
        timeZone = ZoneId.of(json.requiredText("time_zone")),
        times = json.getValue("times").jsonArray.map { element ->
            val time = element.jsonObject
            DoseTime(
                time = LocalTime.parse(time.requiredText("time")),
                quantity = time.decimal("quantity"),
            )
        },
        endDate = json.optionalText("end_date")?.let(LocalDate::parse),
        weekdays = json.optionalInt("weekdays"),
        intervalDays = json.optionalInt("interval_days"),
    )

    private fun movementOf(json: JsonObject): StockMovement = StockMovement(
        id = StockMovementId(json.requiredText("id")),
        medicationId = MedicationId("m1"),
        kind = checkNotNull(StockMovementKind.fromServer(json.requiredText("kind"))) {
            "unknown kind '${json.requiredText("kind")}'"
        },
        quantity = json.decimal("quantity"),
        occurredAt = Instant.parse(json.requiredText("occurred_at")),
    )

    private fun entryOf(json: JsonObject): DoseLogEntry = DoseLogEntry(
        id = DoseLogEntryId(json.requiredText("id")),
        regimenId = RegimenId(json.requiredText("regimen_id")),
        medicationId = MedicationId(json.requiredText("medication_id")),
        scheduledLocal = LocalDateTime.parse(json.requiredText("scheduled_local")),
        outcome = checkNotNull(DoseOutcome.fromServer(json.requiredText("outcome"))) {
            "unknown outcome '${json.requiredText("outcome")}'"
        },
        skipReason = json.optionalText("skip_reason")?.let { value ->
            checkNotNull(SkipReason.fromServer(value)) { "unknown skip_reason '$value'" }
        },
        note = json.optionalText("note"),
        actualAt = Instant.parse(json.requiredText("actual_at")),
        actualQuantity = json.optionalText("actual_quantity")?.let(::BigDecimal),
        confirmedBy = "u1",
        correctsEntryId = json.optionalText("corrects_entry_id")?.let(::DoseLogEntryId),
    )

    private fun assertEqualsDecimal(expected: BigDecimal, actual: BigDecimal, message: String) {
        assertTrue(
            expected.subtract(actual).abs() <= TOLERANCE,
            "$message: expected $expected, got $actual",
        )
    }

    private fun JsonObject.requiredText(name: String): String =
        getValue(name).jsonPrimitive.content

    private fun JsonObject.optionalText(name: String): String? {
        val element: JsonElement = get(name) ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.contentOrNull
    }

    private fun JsonObject.optionalInt(name: String): Int? = optionalText(name)?.toInt()

    private fun JsonObject.decimal(name: String): BigDecimal = BigDecimal(requiredText(name))

    private companion object {
        val TOLERANCE: BigDecimal = BigDecimal("0.000000001")
    }
}
