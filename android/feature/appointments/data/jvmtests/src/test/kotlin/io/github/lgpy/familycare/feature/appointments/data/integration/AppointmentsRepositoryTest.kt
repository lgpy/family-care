package io.github.lgpy.familycare.feature.appointments.data.integration

import com.powersync.db.crud.CrudEntry
import com.powersync.db.crud.UpdateType
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsError
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsResult
import io.github.lgpy.familycare.feature.appointments.data.PowerSyncAppointmentRepository
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentId
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The appointments repository against a **real PowerSync database** built from
 * the merged schema (core + appointments).
 *
 * What this catches that a unit test cannot: the module writes into a PowerSync
 * view through `INSTEAD OF` triggers, so a column the view does not have fails
 * here, and every user action is one queued transaction whose entries these
 * tests read back. No network is involved; the upload itself is exercised by the
 * medications-style upload tests, which need the local Supabase stack.
 */
class AppointmentsRepositoryTest {

    private val local = AppointmentsLocalDatabase(AppointmentsLocalDatabase.testDirectory("appointments-repo"))

    @AfterEach
    fun close() {
        local.close()
    }

    @Test
    fun `creating an appointment queues one insert in the Postgres text form`() = runBlocking {
        seedCircleAndPatient()
        val repository = repository()

        val result = repository.create(appointment())

        assertEquals(AppointmentsResult.Success(AppointmentId(APPOINTMENT_1)), result)
        val transaction = local.database.nextTransaction()
        assertEquals(
            listOf(UpdateType.PUT to "appointments" to APPOINTMENT_1),
            transaction.crud.map { it.op to it.table to it.id },
        )

        val values = transaction.crud.first().values()
        assertEquals(JsonPrimitive(PATIENT_1), values["patient_id"])
        assertEquals(JsonPrimitive("consultation"), values["kind"])
        assertEquals(JsonPrimitive("2026-11-20 09:30:00"), values["starts_local"])
        assertEquals(JsonPrimitive("Europe/Lisbon"), values["time_zone"])
        assertEquals(JsonPrimitive(30L), values["duration_minutes"])
        assertEquals(JsonPrimitive("Cardiologia"), values["title"])
        assertNull(values["cancelled_at"], "A new appointment is not cancelled")
        // The server stamps the audit columns; the phone never sends them.
        assertNull(values["created_at"])
        assertNull(values["created_by"])

        transaction.complete(null)
        assertNull(local.database.getNextCrudTransaction(), "One action queues exactly one transaction")
    }

    @Test
    fun `a created appointment round-trips into the patient's list`() = runBlocking {
        seedCircleAndPatient()
        val repository = repository()

        repository.create(appointment())
        val stored = withTimeout(5_000) {
            repository.observeForPatient(PatientId(PATIENT_1)).first { it.isNotEmpty() }
        }

        assertEquals(1, stored.size)
        val appointment = stored.single()
        assertEquals(AppointmentKind.CONSULTATION, appointment.kind)
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30), appointment.startsLocal)
        assertEquals(ZoneId.of("Europe/Lisbon"), appointment.timeZone)
        assertEquals("Cardiologia", appointment.title)
        assertEquals(Instant.parse("2026-11-20T09:30:00Z"), appointment.startInstant())
    }

    @Test
    fun `cancelling stamps cancelled_at and keeps the row`() = runBlocking {
        seedCircleAndPatient()
        val repository = repository()
        repository.create(appointment())
        local.database.drainUploadQueue()

        val result = repository.cancel(AppointmentId(APPOINTMENT_1))

        assertEquals(AppointmentsResult.Success(Unit), result)
        val transaction = local.database.nextTransaction()
        assertEquals(
            listOf(UpdateType.PATCH to "appointments" to APPOINTMENT_1),
            transaction.crud.map { it.op to it.table to it.id },
        )
        assertEquals(
            JsonPrimitive(NOW.toString()),
            transaction.crud.first().values()["cancelled_at"],
        )

        local.database.drainUploadQueue()
        val stored = withTimeout(5_000) {
            repository.observe(AppointmentId(APPOINTMENT_1)).first { it?.cancelledAt != null }
        }
        assertEquals(NOW, stored?.cancelledAt)
    }

    @Test
    fun `a viewer is refused before anything is queued`() = runBlocking {
        local.seedOwner(role = AppointmentsLocalDatabase.VIEWER)
        local.seedPatient(PATIENT_1)
        local.database.drainUploadQueue()
        val repository = repository()

        val result = repository.create(appointment())

        assertEquals(
            AppointmentsResult.Failure(AppointmentsError.NotAllowed),
            result,
        )
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `an invalid duration is refused before anything is queued`() = runBlocking {
        seedCircleAndPatient()
        val repository = repository()

        val result = repository.create(appointment().copy(durationMinutes = 0))

        assertEquals(
            AppointmentsResult.Failure(AppointmentsError.Invalid(setOf(AppointmentField.DURATION))),
            result,
        )
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `a companion who is not a caregiver of the circle is refused`() = runBlocking {
        seedCircleAndPatient()
        local.seedMembership(userId = COMPANION, role = AppointmentsLocalDatabase.VIEWER)
        local.database.drainUploadQueue()
        val repository = repository()

        val result = repository.create(appointment().copy(companionUserId = COMPANION))

        assertEquals(
            AppointmentsResult.Failure(AppointmentsError.Invalid(setOf(AppointmentField.COMPANION))),
            result,
        )
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `a caregiver may write but only an owner may delete`() = runBlocking {
        local.seedOwner(role = AppointmentsLocalDatabase.CAREGIVER)
        local.seedPatient(PATIENT_1)
        local.database.drainUploadQueue()
        val repository = repository()
        assertEquals(
            AppointmentsResult.Success(AppointmentId(APPOINTMENT_1)),
            repository.create(appointment()),
        )
        local.database.drainUploadQueue()

        val result = repository.delete(AppointmentId(APPOINTMENT_1))

        assertEquals(AppointmentsResult.Failure(AppointmentsError.NotAllowed), result)
        assertNull(local.database.getNextCrudTransaction())
    }

    // --- Fixtures ------------------------------------------------------------

    private suspend fun seedCircleAndPatient() {
        local.seedOwner()
        local.seedPatient(PATIENT_1)
        local.database.drainUploadQueue()
    }

    private fun repository(): AppointmentRepository = PowerSyncAppointmentRepository(
        database = local.database,
        authState = local.authState,
        now = { NOW },
        newId = { APPOINTMENT_1 },
    )

    private fun appointment(): Appointment = Appointment(
        id = AppointmentId(APPOINTMENT_1),
        patientId = PatientId(PATIENT_1),
        kind = AppointmentKind.CONSULTATION,
        startsLocal = LocalDateTime.of(2026, 11, 20, 9, 30),
        timeZone = ZoneId.of("Europe/Lisbon"),
        title = "Cardiologia",
        durationMinutes = 30,
        place = "Hospital da Luz",
        doctor = "Dra. Silva",
        preparation = "Jejum desde as 22:00",
    )

    private companion object {
        const val APPOINTMENT_1 = "33333333-3333-4333-8333-333333333331"
        const val PATIENT_1 = "44444444-4444-4444-8444-444444444441"
        const val COMPANION = "55555555-5555-4555-8555-555555555555"

        val NOW: Instant = Instant.parse("2026-11-18T10:00:00Z")
    }
}

/** The column values a queued entry carries, as the uploader sends them. */
private fun CrudEntry.values(): JsonObject = opData?.jsonValues ?: JsonObject(emptyMap())
