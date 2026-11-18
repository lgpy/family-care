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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The language-neutral vectors in `shared/test-vectors` (D22).
 *
 * The files are on the test classpath because the module's build puts
 * `<repo>/shared/test-vectors` on the test resources (see build.gradle.kts).
 * The same files will be read by the TypeScript server in M4; that is why the
 * expectations are hand-computed and the README spells the rules out.
 */
class SharedVectorTest {

    @Test
    fun `every dose-slot vector matches the calculation`() {
        val root = load("dose-slots.json")
        val cases = root.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty(), "dose-slots.json has no cases")

        cases.forEach { caseElement ->
            val case = caseElement.jsonObject
            val name = case.requiredText("name")
            val regimens = case.getValue("regimens").jsonArray.map { regimenOf(it.jsonObject) }
            val from = LocalDate.parse(case.requiredText("from"))
            val to = LocalDate.parse(case.requiredText("to"))

            val actual = DoseSlotRules.doseSlots(regimens, from, to)
            val expected = case.getValue("expected").jsonArray

            assertEquals(expected.size, actual.size, "slot count for case '$name'")

            expected.forEachIndexed { index, expectedElement ->
                val slot = expectedElement.jsonObject
                val actualSlot = actual[index]
                val where = "case '$name', slot $index"

                assertEquals(
                    slot.requiredText("regimen_id"),
                    actualSlot.regimenId.value,
                    "regimen_id in $where",
                )
                assertEquals(
                    LocalDateTime.parse(slot.requiredText("scheduled_local")),
                    actualSlot.scheduledLocal,
                    "scheduled_local in $where",
                )
                assertEquals(
                    Instant.parse(slot.requiredText("instant")),
                    actualSlot.instant,
                    "instant in $where",
                )
                assertEquals(
                    0,
                    slot.decimal("quantity").compareTo(actualSlot.quantity),
                    "quantity (${slot.requiredText("quantity")} vs ${actualSlot.formattedQuantity}) in $where",
                )
            }
        }
    }

    @Test
    fun `every dose-status vector matches the derivation`() {
        val root = load("dose-status.json")
        val defaultEscalation = root.requiredInt("escalation_delay_minutes")
        val defaultMissed = root.requiredInt("missed_window_minutes")
        val cases = root.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty(), "dose-status.json has no cases")

        cases.forEach { caseElement ->
            val case = caseElement.jsonObject
            val name = case.requiredText("name")
            val slot = slotOf(case.getValue("slot").jsonObject)
            val entries = case.get("entries")?.jsonArray.orEmpty().map { entryOf(it.jsonObject) }
            val now = Instant.parse(case.requiredText("now"))
            val escalation = case.optionalInt("escalation_delay_minutes") ?: defaultEscalation
            val missed = case.optionalInt("missed_window_minutes") ?: defaultMissed

            val actual = DoseStatusRules.doseStatus(slot, entries, now, escalation, missed)
            val expected = case.getValue("expected").jsonObject

            assertEquals(
                expected.requiredText("state").uppercase(),
                actual.state.name,
                "state in case '$name'",
            )
            assertEquals(
                expected.requiredInt("confirmation_count"),
                actual.confirmationCount,
                "confirmation_count in case '$name'",
            )
            assertEquals(
                expected.getValue("confirmed_twice").jsonPrimitive.boolean,
                actual.confirmedTwice,
                "confirmed_twice in case '$name'",
            )
        }
    }

    @Test
    fun `the vector files pin the DST weeks and the versioned change`() {
        val names = load("dose-slots.json").getValue("cases").jsonArray
            .map { it.jsonObject.requiredText("name") }

        listOf(
            "daily-multi-time-fractional",
            "weekdays-mask-mon-wed-fri",
            "every-three-days-anchored-mid-range",
            "start-and-end-bounds-inclusive",
            "regimen-change-two-versions-no-gap-no-overlap",
            "spring-forward-lisbon-2026-03-23-to-29",
            "autumn-lisbon-2026-10-19-to-25",
            "sao-paulo-no-dst",
            "leap-day-2028-every-seven-days",
        ).forEach { required ->
            assertTrue(required in names, "dose-slots.json lost the '$required' case")
        }
    }

    // --- Reading the files -------------------------------------------------

    private fun load(name: String): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/$name")) {
            "shared vector '$name' is not on the test classpath " +
                "(expected it from <repo>/shared/test-vectors)"
        }
        return stream.bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
        }
    }

    private fun JsonObject.requiredText(name: String): String =
        getValue(name).jsonPrimitive.content

    private fun JsonObject.requiredInt(name: String): Int =
        getValue(name).jsonPrimitive.int

    private fun JsonObject.optionalText(name: String): String? {
        val element: JsonElement = get(name) ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.contentOrNull
    }

    private fun JsonObject.optionalInt(name: String): Int? = optionalText(name)?.toInt()

    private fun JsonObject.decimal(name: String): BigDecimal = BigDecimal(requiredText(name))

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
                time = java.time.LocalTime.parse(time.requiredText("time")),
                quantity = time.decimal("quantity"),
            )
        },
        endDate = json.optionalText("end_date")?.let(LocalDate::parse),
        weekdays = json.optionalInt("weekdays"),
        intervalDays = json.optionalInt("interval_days"),
    )

    private fun slotOf(json: JsonObject): DoseSlot = DoseSlot(
        regimenId = RegimenId(json.requiredText("regimen_id")),
        medicationId = MedicationId(json.requiredText("medication_id")),
        scheduledLocal = LocalDateTime.parse(json.requiredText("scheduled_local")),
        instant = Instant.parse(json.requiredText("instant")),
        quantity = json.decimal("quantity"),
    )

    private fun entryOf(json: JsonObject): DoseLogEntry = DoseLogEntry(
        id = DoseLogEntryId(json.requiredText("id")),
        regimenId = RegimenId(json.requiredText("regimen_id")),
        medicationId = MedicationId(json.requiredText("medication_id")),
        scheduledLocal = LocalDateTime.parse(json.requiredText("scheduled_local")),
        outcome = checkNotNull(
            DoseOutcome.fromServer(json.requiredText("outcome")),
        ) { "unknown outcome '${json.requiredText("outcome")}'" },
        skipReason = json.optionalText("skip_reason")?.let { value ->
            checkNotNull(SkipReason.fromServer(value)) { "unknown skip_reason '$value'" }
        },
        note = json.optionalText("note"),
        actualAt = Instant.parse(json.requiredText("actual_at")),
        actualQuantity = json.optionalText("actual_quantity")?.let(::BigDecimal),
        confirmedBy = json.requiredText("confirmed_by"),
        givenByUserId = json.optionalText("given_by_user_id"),
        givenByOther = json.getValue("given_by_other").jsonPrimitive.boolean,
        givenByName = json.optionalText("given_by_name"),
        correctsEntryId = json.optionalText("corrects_entry_id")?.let(::DoseLogEntryId),
    )
}
