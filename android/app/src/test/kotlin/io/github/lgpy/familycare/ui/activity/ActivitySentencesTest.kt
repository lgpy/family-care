package io.github.lgpy.familycare.ui.activity

import io.github.lgpy.familycare.core.data.activity.ActivityEvent
import io.github.lgpy.familycare.core.ui.R
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `(table_name, action)` → sentence mapping of the activity feed (D30). */
class ActivitySentencesTest {

    @Test
    fun medicationInsertNamesTheActorTheMedicationAndThePatient() {
        val sentence = activitySentence(
            event(table = "medications", action = "insert", label = "Metformin"),
            someone = SOMEONE,
        )

        assertEquals(R.string.activity_added_medication, sentence.template)
        assertEquals(listOf("Leonardo", "Metformin", "Avó"), sentence.args)
    }

    @Test
    fun aSchemaQualifiedTableNameStillMaps() {
        val sentence = activitySentence(
            event(table = "meds.medications", action = "delete", label = "Metformin"),
            someone = SOMEONE,
        )

        assertEquals(R.string.activity_removed_medication, sentence.template)
    }

    @Test
    fun aDoseLogInsertIsARecordedDose() {
        val sentence = activitySentence(
            event(table = "dose_log", action = "insert", label = "Metformin"),
            someone = SOMEONE,
        )

        assertEquals(R.string.activity_recorded_dose, sentence.template)
        assertEquals(listOf("Leonardo", "Metformin", "Avó"), sentence.args)
    }

    @Test
    fun appointmentsDistinguishInsertUpdateAndDelete() {
        assertEquals(
            R.string.activity_added_appointment,
            activitySentence(event(table = "appointments", action = "insert"), SOMEONE).template,
        )
        assertEquals(
            R.string.activity_updated_appointment,
            activitySentence(event(table = "appointments", action = "update"), SOMEONE).template,
        )
        assertEquals(
            R.string.activity_removed_appointment,
            activitySentence(event(table = "appointments", action = "delete"), SOMEONE).template,
        )
    }

    @Test
    fun aPatientInfoUpdateHasTwoArguments() {
        val sentence = activitySentence(event(table = "patient_info", action = "update"), SOMEONE)

        assertEquals(R.string.activity_updated_patient_info, sentence.template)
        assertEquals(listOf("Leonardo", "Avó"), sentence.args)
    }

    @Test
    fun aPatientUpdateUsesThePatientName() {
        val sentence = activitySentence(event(table = "patients", action = "update"), SOMEONE)

        assertEquals(R.string.activity_updated_patient, sentence.template)
        assertEquals(listOf("Leonardo", "Avó"), sentence.args)
    }

    @Test
    fun securityEventsMapToTheirOwnSentences() {
        assertEquals(
            R.string.activity_signed_in,
            activitySentence(event(table = "auth.sessions", action = "auth.sign_in"), SOMEONE).template,
        )
        assertEquals(
            R.string.activity_signed_out,
            activitySentence(event(table = "auth.sessions", action = "auth.session_ended"), SOMEONE).template,
        )
        assertEquals(
            R.string.activity_erased_patient,
            activitySentence(event(table = "patients", action = "patient.erased"), SOMEONE).template,
        )

        val exported = activitySentence(
            event(table = "patients", action = "patient.exported", label = null),
            SOMEONE,
        )
        assertEquals(R.string.activity_exported_patient_data, exported.template)
        assertEquals(listOf("Leonardo", "Avó"), exported.args)
    }

    @Test
    fun membershipsAndInvitationsAreTheCircleSentence() {
        assertEquals(
            R.string.activity_changed_circle,
            activitySentence(event(table = "memberships", action = "insert"), SOMEONE).template,
        )
        assertEquals(
            R.string.activity_changed_invitations,
            activitySentence(event(table = "invitations", action = "insert"), SOMEONE).template,
        )
    }

    @Test
    fun anUnknownTableFallsBackToTheGenericSentence() {
        val sentence = activitySentence(event(table = "meds.stock_movements", action = "insert"), SOMEONE)

        assertEquals(R.string.activity_changed, sentence.template)
        assertEquals(listOf("Leonardo", "Metformin"), sentence.args)
    }

    @Test
    fun aMissingActorNameIsTheLocalizedPlaceholder() {
        val sentence = activitySentence(
            event(table = "medications", action = "insert", actorName = null),
            someone = "Alguém",
        )

        assertEquals(listOf("Alguém", "Metformin", "Avó"), sentence.args)
    }

    @Test
    fun aBlankPatientNameIsThePlaceholderToo() {
        val sentence = activitySentence(
            event(table = "patient_info", action = "update", patientName = "  "),
            someone = SOMEONE,
        )

        assertEquals(listOf("Leonardo", SOMEONE), sentence.args)
    }

    @Test
    fun eventsAreGroupedByLocalDayNewestFirst() {
        val late = event(id = "late", occurredAt = "2026-11-18T22:00:00Z")
        val early = event(id = "early", occurredAt = "2026-11-18T08:00:00Z")
        val previous = event(id = "previous", occurredAt = "2026-11-17T23:30:00Z")

        val days = activityDays(listOf(late, early, previous), ZoneId.of("UTC"))

        assertEquals(
            listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 17)),
            days.map { it.date },
        )
        assertEquals(listOf("late", "early"), days[0].events.map { it.id })
        assertEquals(listOf("previous"), days[1].events.map { it.id })
    }

    private fun event(
        id: String = "event-1",
        occurredAt: String = "2026-11-18T10:00:00Z",
        table: String = "medications",
        action: String = "insert",
        actorName: String? = "Leonardo",
        patientName: String? = "Avó",
        label: String? = "Metformin",
    ) = ActivityEvent(
        id = id,
        occurredAt = Instant.parse(occurredAt),
        eventKind = if (action.startsWith("auth.") || action.startsWith("patient.")) {
            "security"
        } else {
            "data"
        },
        action = action,
        tableName = table,
        actorName = actorName,
        patientName = patientName,
        label = label,
    )

    private companion object {
        const val SOMEONE = "Someone"
    }
}
