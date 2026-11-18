package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text and JSON contract between the server and this module: `times`,
 * `scheduled_local`, `date` columns, decimal quantities and enum values.
 *
 * Nothing here touches a database: the conversions are pure functions and the
 * row types are plain data, so the parsing rules are pinned down without a
 * PowerSync cursor. The same values are exercised against a real database in
 * `:feature:medications:data:jvmtests`.
 */
class MedsRowsTest {

    // --- regimens.times (jsonb) ---------------------------------------------

    @Test
    fun `times json is parsed into ascending dose times with exact quantities`() {
        val times = parseTimes(
            """[{"time":"21:30","quantity":0.125},{"time":"08:00","quantity":1}]""",
        )

        assertEquals(
            listOf(
                DoseTime(time = LocalTime.of(8, 0), quantity = BigDecimal("1")),
                DoseTime(time = LocalTime.of(21, 30), quantity = BigDecimal("0.125")),
            ),
            times,
        )
    }

    @Test
    fun `times are formatted as the compact json array postgres stores`() {
        val json = formatTimes(
            listOf(
                DoseTime(time = LocalTime.of(21, 30), quantity = BigDecimal("0.125")),
                DoseTime(time = LocalTime.of(8, 0), quantity = BigDecimal("0.500")),
            ),
        )

        // Ascending by time, plain decimal quantities (never "0.500"), no spaces:
        // what the server's times_are_valid check accepts.
        assertEquals("""[{"time":"08:00","quantity":0.5},{"time":"21:30","quantity":0.125}]""", json)
    }

    @Test
    fun `a times array with one unreadable item is refused whole`() {
        assertNull(parseTimes("""[{"time":"08:00","quantity":1},{"time":"noon","quantity":1}]"""))
        assertNull(parseTimes("""[{"time":"08:00","quantity":1},{"time":"09:00","quantity":"one"}]"""))
        assertNull(parseTimes("""[{"time":"08:00","quantity":1},{"quantity":1}]"""))
    }

    @Test
    fun `empty, missing and non-array times are refused`() {
        assertNull(parseTimes(null))
        assertNull(parseTimes(""))
        assertNull(parseTimes("[]"))
        assertNull(parseTimes("""{"time":"08:00","quantity":1}"""))
        assertNull(parseTimes("not json"))
    }

    // --- Quantities ---------------------------------------------------------

    @Test
    fun `decimal quantities keep their exact value, including halves and eighths`() {
        assertEquals("0.5", parseQuantity("0.5")!!.toPlainString())
        assertEquals("0.125", parseQuantity("0.125")!!.toPlainString())
        assertEquals("1.500", parseQuantity("1.500")!!.toPlainString())
        assertEquals("0.5", formatQuantity(BigDecimal("0.500")))
        assertEquals("0.125", formatQuantity(BigDecimal("0.125")))
        assertEquals("1", formatQuantity(BigDecimal("1.000")))
        assertNull(parseQuantity(null))
        assertNull(parseQuantity("half"))
        assertNull(parseQuantity(""))
    }

    // --- date / timestamp columns ------------------------------------------

    @Test
    fun `dates round-trip as YYYY-MM-DD`() {
        assertEquals("2026-11-19", formatDate(LocalDate.of(2026, 11, 19)))
        assertEquals(LocalDate.of(2026, 11, 19), parseDate("2026-11-19"))
        assertNull(parseDate(null))
        assertNull(parseDate(""))
        assertNull(parseDate("19/11/2026"))
    }

    @Test
    fun `scheduled local accepts the postgres space form and the iso forms`() {
        val expected = LocalDateTime.of(2026, 11, 19, 8, 30)

        assertEquals(expected, parseScheduledLocal("2026-11-19 08:30:00"))
        assertEquals(expected, parseScheduledLocal("2026-11-19T08:30"))
        assertEquals(expected, parseScheduledLocal("2026-11-19 08:30:00.123"))
        assertEquals(expected, parseScheduledLocal("2026-11-19T08:30:00Z"))
        assertEquals(expected, parseScheduledLocal("2026-11-19T08:30:00+00:00"))
    }

    @Test
    fun `scheduled local is written in the postgres space form`() {
        assertEquals(
            "2026-11-19 07:05:03",
            formatScheduledLocal(LocalDateTime.of(2026, 11, 19, 7, 5, 3)),
        )
    }

    @Test
    fun `a malformed scheduled local is not a local date-time`() {
        assertNull(parseScheduledLocal(null))
        assertNull(parseScheduledLocal("  "))
        assertNull(parseScheduledLocal("tomorrow 08:00"))
    }

    // --- Medication rows ----------------------------------------------------

    @Test
    fun `a medication row becomes the domain medication`() {
        val medication = medicationRow(
            requiresPrescription = 1,
            prescriptionValidUntil = "2026-12-31",
            stoppedAt = "2026-11-18T10:00:00.000Z",
            regimen = regimenRow(),
        ).toMedication()

        assertNotNull(medication)
        medication!!
        assertEquals("med-1", medication.id.value)
        assertEquals(PatientId("patient-1"), medication.patientId)
        assertEquals("Metformin", medication.name)
        assertEquals(MedicationForm.TABLET, medication.form)
        assertTrue(medication.requiresPrescription)
        assertEquals(LocalDate.of(2026, 12, 31), medication.prescriptionValidUntil)
        assertEquals(Instant.parse("2026-11-18T10:00:00Z"), medication.stoppedAt)
        assertEquals(30, medication.lowStockThresholdDays)
    }

    @Test
    fun `an unknown form or reminder policy skips the medication instead of crashing`() {
        assertNull(medicationRow(form = "lollipop").toMedication())
        assertNull(medicationRow(reminderPolicy = "telepathy").toMedication())
        assertNull(medicationRow(prescriptionValidUntil = "someday").toMedication())
    }

    @Test
    fun `the patient settings come from the joined patient row`() {
        val patient = medicationRow().toPatient()

        assertEquals(PatientId("patient-1"), patient.patientId)
        assertEquals("Grandma", patient.displayName)
        assertEquals(15, patient.escalationDelayMinutes)
        assertEquals(90, patient.missedWindowMinutes)
    }

    @Test
    fun `a regimen row becomes the domain regimen`() {
        val regimen = regimenRow().toRegimen()

        assertNotNull(regimen)
        regimen!!
        assertEquals("regimen-1", regimen.id.value)
        assertEquals(ScheduleType.DAILY, regimen.scheduleType)
        assertEquals(LocalDate.of(2026, 11, 1), regimen.startDate)
        assertEquals(ZoneId.of("Europe/Lisbon"), regimen.timeZone)
        assertEquals(listOf(LocalTime.of(8, 0)), regimen.times.map { it.time })
    }

    @Test
    fun `a version with an unknown schedule or zone is skipped`() {
        assertNull(regimenRow(scheduleType = "hourly").toRegimen())
        assertNull(regimenRow(timeZone = "Mars/Olympus").toRegimen())
        assertNull(regimenRow(startDate = "2026-11-31").toRegimen())
        assertNull(regimenRow(times = "[]").toRegimen())
    }

    @Test
    fun `an unreadable version only loses that version, not the medication`() {
        val rows = listOf(
            medicationRow(regimen = regimenRow(id = "regimen-1")),
            medicationRow(regimen = regimenRow(id = "regimen-2", scheduleType = "hourly")),
        )

        val medications = rows.toMedicationWithRegimens()

        assertEquals(1, medications.size)
        assertEquals(listOf("regimen-1"), medications.single().regimens.map { it.id.value })
    }

    // --- Dose-log rows ------------------------------------------------------

    @Test
    fun `a dose log row becomes the domain entry`() {
        val entry = doseLogRow(
            outcome = "skipped",
            skipReason = "refused",
            actualQuantity = null,
            givenByOther = 1,
            givenByName = "Neighbour",
            correctsEntryId = "entry-0",
        ).toEntry()

        assertNotNull(entry)
        entry!!
        assertEquals("entry-1", entry.id.value)
        assertEquals(DoseOutcome.SKIPPED, entry.outcome)
        assertEquals(SkipReason.REFUSED, entry.skipReason)
        assertEquals(LocalDateTime.of(2026, 11, 19, 8, 0), entry.scheduledLocal)
        assertEquals(Instant.parse("2026-11-19T08:04:00Z"), entry.actualAt)
        assertTrue(entry.givenByOther)
        assertEquals("Neighbour", entry.givenByName)
        assertEquals("entry-0", entry.correctsEntryId?.value)
        assertEquals(null, entry.actualQuantity)
    }

    @Test
    fun `a taken entry keeps its exact quantity`() {
        val entry = doseLogRow(actualQuantity = "0.125").toEntry()

        assertEquals(BigDecimal("0.125"), entry!!.actualQuantity)
        assertFalse(entry.givenByOther)
    }

    @Test
    fun `an unknown outcome or skip reason skips the entry`() {
        assertNull(doseLogRow(outcome = "maybe").toEntry())
        assertNull(doseLogRow(outcome = "skipped", skipReason = "bored").toEntry())
        assertNull(doseLogRow(actualAt = "not a timestamp").toEntry())
        assertNull(doseLogRow(scheduledLocal = "not a slot").toEntry())
        assertNull(doseLogRow(actualQuantity = "half").toEntry())
    }

    @Test
    fun `unreadable entries are dropped from a list`() {
        val entries = listOf(doseLogRow(), doseLogRow(id = "entry-2", outcome = "maybe")).toEntries()

        assertEquals(listOf("entry-1"), entries.map { it.id.value })
    }

    // --- Builders -----------------------------------------------------------

    private fun medicationRow(
        id: String = "med-1",
        form: String = "tablet",
        requiresPrescription: Long = 0,
        prescriptionValidUntil: String? = null,
        lowStockThresholdDays: Long = 30,
        reminderPolicy: String? = "primary_with_escalation",
        stoppedAt: String? = null,
        regimen: RegimenRow? = null,
    ) = MedicationRow(
        id = id,
        patientId = "patient-1",
        name = "Metformin",
        activeIngredient = "metformin",
        strength = "500 mg",
        form = form,
        stockUnit = "tablet",
        instructions = "with food",
        requiresPrescription = requiresPrescription,
        prescriptionValidUntil = prescriptionValidUntil,
        lowStockThresholdDays = lowStockThresholdDays,
        reminderPolicy = reminderPolicy,
        primaryCaregiverId = null,
        stoppedAt = stoppedAt,
        patientName = "Grandma",
        patientEscalationDelayMinutes = 15,
        patientMissedWindowMinutes = 90,
        regimen = regimen,
    )

    private fun regimenRow(
        id: String = "regimen-1",
        scheduleType: String = "daily",
        startDate: String = "2026-11-01",
        endDate: String? = null,
        timeZone: String = "Europe/Lisbon",
        times: String = """[{"time":"08:00","quantity":0.5}]""",
    ) = RegimenRow(
        id = id,
        medicationId = "med-1",
        scheduleType = scheduleType,
        weekdays = null,
        intervalDays = null,
        startDate = startDate,
        endDate = endDate,
        timeZone = timeZone,
        times = times,
    )

    private fun doseLogRow(
        id: String = "entry-1",
        outcome: String = "taken",
        skipReason: String? = null,
        scheduledLocal: String = "2026-11-19 08:00:00",
        actualAt: String = "2026-11-19T08:04:00.000Z",
        actualQuantity: String? = "0.500",
        givenByOther: Long = 0,
        givenByName: String? = null,
        correctsEntryId: String? = null,
    ) = DoseLogRow(
        id = id,
        regimenId = "regimen-1",
        medicationId = "med-1",
        scheduledLocal = scheduledLocal,
        outcome = outcome,
        skipReason = skipReason,
        note = null,
        actualAt = actualAt,
        actualQuantity = actualQuantity,
        confirmedBy = "user-1",
        givenByUserId = "user-1",
        givenByOther = givenByOther,
        givenByName = givenByName,
        correctsEntryId = correctsEntryId,
    )
}
