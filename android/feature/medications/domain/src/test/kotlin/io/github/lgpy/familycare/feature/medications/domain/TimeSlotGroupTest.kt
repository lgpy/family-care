package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.PatientId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** Grouping for "confirm a whole time slot" (docs/ux/screens.md §2). */
class TimeSlotGroupTest {

    private val mom = PatientId("patient-mom")
    private val grandma = PatientId("patient-grandma")

    @Test
    fun `medications of one patient at one local time form one group`() {
        val slots = listOf(
            Fixtures.slot(medicationId = MedicationId("med-a")),
            Fixtures.slot(medicationId = MedicationId("med-b")),
            Fixtures.slot(medicationId = MedicationId("med-c")),
        )

        val groups = groupTimeSlots(slots) { mom }

        assertEquals(1, groups.size)
        assertEquals(mom, groups.single().patientId)
        assertEquals(LocalDateTime.parse("2026-11-16T08:00"), groups.single().scheduledLocal)
        assertEquals(3, groups.single().slotCount)
        assertEquals(
            listOf("med-a", "med-b", "med-c"),
            groups.single().medications.map { it.value },
        )
    }

    @Test
    fun `the same time for two patients is two groups`() {
        val slots = listOf(
            Fixtures.slot(medicationId = MedicationId("med-mom")),
            Fixtures.slot(medicationId = MedicationId("med-grandma")),
        )
        val patientOf = mapOf(
            MedicationId("med-mom") to mom,
            MedicationId("med-grandma") to grandma,
        )

        val groups = groupTimeSlots(slots) { patientOf[it] }

        assertEquals(2, groups.size)
        assertEquals(
            listOf(grandma, mom),
            groups.map { it.patientId },
        )
    }

    @Test
    fun `different local times are different groups`() {
        val slots = listOf(
            Fixtures.slot(
                medicationId = MedicationId("med-a"),
                scheduledLocal = "2026-11-16T21:00",
                instant = "2026-11-16T21:00:00Z",
            ),
            Fixtures.slot(medicationId = MedicationId("med-a")),
        )

        val groups = groupTimeSlots(slots) { mom }

        assertEquals(2, groups.size)
        assertEquals(
            listOf(
                LocalDateTime.parse("2026-11-16T08:00"),
                LocalDateTime.parse("2026-11-16T21:00"),
            ),
            groups.map { it.scheduledLocal },
        )
    }

    @Test
    fun `groups are ordered by local time`() {
        val slots = listOf(
            Fixtures.slot(
                medicationId = MedicationId("med-b"),
                scheduledLocal = "2026-11-17T08:00",
                instant = "2026-11-17T08:00:00Z",
            ),
            Fixtures.slot(medicationId = MedicationId("med-a")),
            Fixtures.slot(
                medicationId = MedicationId("med-c"),
                scheduledLocal = "2026-11-16T21:00",
                instant = "2026-11-16T21:00:00Z",
            ),
        )

        val groups = groupTimeSlots(slots) { mom }

        assertEquals(
            listOf(
                LocalDateTime.parse("2026-11-16T08:00"),
                LocalDateTime.parse("2026-11-16T21:00"),
                LocalDateTime.parse("2026-11-17T08:00"),
            ),
            groups.map { it.scheduledLocal },
        )
    }

    @Test
    fun `a slot whose medication is unknown is skipped`() {
        val known = Fixtures.slot(medicationId = MedicationId("med-known"))
        val unknown = Fixtures.slot(medicationId = MedicationId("med-unknown"))

        val groups = groupTimeSlots(listOf(known, unknown)) { id ->
            if (id.value == "med-known") mom else null
        }

        assertEquals(1, groups.size)
        assertEquals(listOf("med-known"), groups.single().medications.map { it.value })
    }

    @Test
    fun `the patient is resolved from the medication rows`() {
        val medication = Fixtures.medication(id = MedicationId("med-a"), patientId = grandma)
        val slots = listOf(Fixtures.slot(medicationId = MedicationId("med-a")))

        val groups = groupTimeSlots(slots, listOf(medication))

        assertEquals(grandma, groups.single().patientId)
    }

    @Test
    fun `the group total quantity is the numeric sum`() {
        val slots = listOf(
            Fixtures.slot(medicationId = MedicationId("med-a"), quantity = "0.5"),
            Fixtures.slot(medicationId = MedicationId("med-b"), quantity = "0.25"),
        )

        val group = groupTimeSlots(slots) { mom }.single()

        assertEquals(0, java.math.BigDecimal("0.75").compareTo(group.totalQuantity))
    }

    @Test
    fun `grouping an empty list is empty not an error`() {
        assertTrue(groupTimeSlots(emptyList<DoseSlot>()) { mom }.isEmpty())
    }
}
