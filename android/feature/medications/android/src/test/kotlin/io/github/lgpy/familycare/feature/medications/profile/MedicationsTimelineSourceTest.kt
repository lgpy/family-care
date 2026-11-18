package io.github.lgpy.familycare.feature.medications.profile

import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.TimelineKind
import io.github.lgpy.familycare.core.ui.patient.TimelineText
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.testPatientId
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The medications History entries: start, every change and stop (D29). */
class MedicationsTimelineSourceTest {

    private val medications = FakeMedicationRepository()
    private val source = MedicationsTimelineSource(medications, FakeTimelineText)

    @Test
    fun aFirstVersionIsOneStartedEntry() = runBlocking {
        medications.setPatientMedications(
            testPatientId,
            listOf(
                withRegimens(
                    med = medication(name = "Metformin", strength = "500 mg"),
                    regimens = listOf(regimen(startDate = LocalDate.of(2026, 1, 1))),
                ),
            ),
        )

        val entries = source.entries(testPatientId).first()

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("Started Metformin 500 mg", entry.title)
        assertNull(entry.detail)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), entry.at)
        assertEquals(
            "patients/patient-grandpa/medications/med-1",
            entry.route,
        )
    }

    @Test
    fun everyLaterVersionAndTheStopBecomeEntries() = runBlocking {
        medications.setPatientMedications(
            testPatientId,
            listOf(
                withRegimens(
                    med = medication(
                        name = "Metformin",
                        strength = "500 mg",
                        stoppedAt = Instant.parse("2026-11-01T09:00:00Z"),
                    ),
                    regimens = listOf(
                        regimen(
                            id = "reg-1",
                            medicationId = "med-1",
                            startDate = LocalDate.of(2026, 1, 1),
                            endDate = LocalDate.of(2026, 2, 28),
                        ),
                        regimen(
                            id = "reg-2",
                            medicationId = "med-1",
                            startDate = LocalDate.of(2026, 3, 1),
                        ),
                    ),
                ),
            ),
        )

        val entries = source.entries(testPatientId).first().sortedBy { it.at }

        assertEquals(3, entries.size)
        assertEquals("Started Metformin 500 mg", entries[0].title)
        assertEquals("Changed Metformin", entries[1].title)
        assertEquals("New dose or schedule", entries[1].detail)
        assertEquals("Stopped Metformin", entries[2].title)
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"), entries[2].at)
        assertEquals(TimelineKind.MEDICATION, entries[2].kind)
    }

    @Test
    fun anActiveMedicationHasNoStopEntry() = runBlocking {
        medications.setPatientMedications(
            testPatientId,
            listOf(withRegimens(med = medication(stoppedAt = null))),
        )

        assertEquals(
            listOf("Started Metformin 500 mg"),
            source.entries(testPatientId).first().map { it.title },
        )
    }

    /** The strings the source asks for, without Android's Resources. */
    private object FakeTimelineText : TimelineText {
        override fun get(id: Int, vararg args: Any?): String = when (id) {
            R.string.patient_timeline_medication_started -> "Started " + args.joinToString(" ")
            R.string.patient_timeline_medication_changed -> "Changed " + args.joinToString(" ")
            R.string.patient_timeline_medication_changed_detail -> "New dose or schedule"
            R.string.patient_timeline_medication_stopped -> "Stopped " + args.joinToString(" ")
            else -> "unknown"
        }
    }
}
