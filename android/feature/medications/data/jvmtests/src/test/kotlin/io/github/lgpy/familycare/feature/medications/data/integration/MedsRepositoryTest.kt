package io.github.lgpy.familycare.feature.medications.data.integration

import com.powersync.db.crud.CrudEntry
import com.powersync.db.crud.CrudTransaction
import com.powersync.db.crud.UpdateType
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.PowerSyncDoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.PowerSyncMedicationRepository
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntryId
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseSlotRules
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.domain.groupTimeSlots
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The medications repositories against a **real PowerSync database** built from
 * the merged schema (core + meds).
 *
 * What this catches that a unit test cannot: the module writes into PowerSync
 * views through `INSTEAD OF` triggers, so a local `INSERT ... ON CONFLICT` (the
 * M2 bug) or a column the view does not have fails here, and every user action
 * is one queued transaction whose entries these tests read back.
 *
 * Every test seeds the circle, the signed-in membership and (usually) a patient,
 * then drains the queue so only the action under test is asserted.
 */
class MedsRepositoryTest {

    private val local = MedsLocalDatabase(MedsLocalDatabase.testDirectory("meds-repo"))

    @AfterEach
    fun close() {
        local.close()
    }

    // --- Medication + first regimen -----------------------------------------

    @Test
    fun `creating a medication and its first regimen queues them as one transaction`() = runBlocking {
        seedCircleAndPatient()
        val repository = medicationRepository()

        val result = repository.create(medication(MED_1), regimen(REGIMEN_1, MED_1))

        assertEquals(MedsResult.Success(MedicationId(MED_1)), result)
        // One action is one upload transaction: exactly these two entries, in order.
        val transaction = local.database.nextTransaction()
        assertEquals(
            listOf(
                UpdateType.PUT to "medications" to MED_1,
                UpdateType.PUT to "regimens" to REGIMEN_1,
            ),
            transaction.crud.map { it.op to it.table to it.id },
        )

        val medication = transaction.crud.first().values()
        assertEquals(JsonPrimitive(PATIENT_1), medication["patient_id"])
        assertEquals(JsonPrimitive("Metformin"), medication["name"])
        assertEquals(JsonPrimitive(1L), medication["requires_prescription"])

        val firstRegimen = transaction.crud.last().values()
        assertEquals(JsonPrimitive(MED_1), firstRegimen["medication_id"])
        assertEquals(JsonPrimitive("daily"), firstRegimen["schedule_type"])
        assertEquals(JsonPrimitive("2026-11-01"), firstRegimen["start_date"])
        // The module's own text form: a compact array, ascending by time.
        assertEquals(
            JsonPrimitive("""[{"time":"08:00","quantity":0.5},{"time":"21:00","quantity":1}]"""),
            firstRegimen["times"],
        )

        // A medication and the schedule that makes it useful can never half-apply.
        transaction.complete(null)
        assertNull(local.database.getNextCrudTransaction(), "One action queues exactly one transaction")
    }

    @Test
    fun `a validation error is returned before anything is written`() = runBlocking {
        seedCircleAndPatient()
        val repository = medicationRepository()

        val result = repository.create(
            medication(MED_1, name = ""),
            regimen(REGIMEN_1, MED_1),
        )

        assertInstanceOf(MedsResult.Failure::class.java, result)
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `a regimen for another medication is refused`() = runBlocking {
        seedCircleAndPatient()
        val repository = medicationRepository()

        val result = repository.create(medication(MED_1), regimen(REGIMEN_1, MED_2))

        assertInstanceOf(MedsResult.Failure::class.java, result)
        assertNull(local.database.getNextCrudTransaction())
    }

    // --- Regimen versioning (D9) -------------------------------------------

    @Test
    fun `changing a regimen ends the old version and inserts the new one`() = runBlocking {
        seedCircleAndPatient()
        val created = medicationRepository()
        assertEquals(MedsResult.Success(MedicationId(MED_1)), created.create(medication(MED_1), regimen(REGIMEN_1, MED_1)))
        local.database.drainUploadQueue()

        val repository = medicationRepository(newId = { REGIMEN_2 })
        val result = repository.changeRegimen(
            current = regimen(REGIMEN_1, MED_1),
            effectiveFrom = LocalDate.of(2026, 11, 10),
            times = listOf(DoseTime(LocalTime.of(9, 0), BigDecimal("1"))),
        )

        assertEquals(MedsResult.Success(RegimenId(REGIMEN_2)), result)
        val transaction = local.database.nextTransaction()
        assertEquals(
            listOf(
                UpdateType.PATCH to "regimens" to REGIMEN_1,
                UpdateType.PUT to "regimens" to REGIMEN_2,
            ),
            transaction.crud.map { it.op to it.table to it.id },
        )

        // Only end_date: the server's trigger refuses every other change.
        assertEquals(
            JsonObject(mapOf("end_date" to JsonPrimitive("2026-11-09"))),
            transaction.crud.first().values(),
        )
        val started = transaction.crud.last().values()
        assertEquals(JsonPrimitive("2026-11-10"), started["start_date"])
        assertNull(started["end_date"], "An open-ended new version sends no end date")
    }

    @Test
    fun `a change on the current version's start day is refused`() = runBlocking {
        seedCircleAndPatient()
        val created = medicationRepository()
        created.create(medication(MED_1), regimen(REGIMEN_1, MED_1))
        local.database.drainUploadQueue()

        val result = medicationRepository(newId = { REGIMEN_2 })
            .changeRegimen(regimen(REGIMEN_1, MED_1), effectiveFrom = LocalDate.of(2026, 11, 1))

        assertInstanceOf(MedsResult.Failure::class.java, result)
        assertNull(local.database.getNextCrudTransaction())
    }

    // --- Stopping -----------------------------------------------------------

    @Test
    fun `stopping sets the regimen's end date and the medication's stopped_at`() = runBlocking {
        seedCircleAndPatient()
        val repository = medicationRepository()
        repository.create(medication(MED_1), regimen(REGIMEN_1, MED_1))
        local.database.drainUploadQueue()

        val result = repository.stop(medication(MED_1), regimen(REGIMEN_1, MED_1), LocalDate.of(2026, 11, 20))

        assertEquals(MedsResult.Success(Unit), result)
        val transaction = local.database.nextTransaction()
        assertEquals(
            listOf(
                UpdateType.PATCH to "regimens" to REGIMEN_1,
                UpdateType.PATCH to "medications" to MED_1,
            ),
            transaction.crud.map { it.op to it.table to it.id },
        )
        assertEquals(
            JsonObject(mapOf("end_date" to JsonPrimitive("2026-11-20"))),
            transaction.crud.first().values(),
        )
        assertEquals(
            JsonPrimitive(NOW.toString()),
            transaction.crud.last().values()["stopped_at"],
        )
        // The local row must agree with the queue, or the medication would stay on
        // the Today screen after being stopped.
        val stopped = medicationRepository().observeMedication(MedicationId(MED_1)).first()!!.medication
        assertTrue(stopped.isStopped)
    }

    @Test
    fun `stopping before the start day is refused`() = runBlocking {
        seedCircleAndPatient()
        val repository = medicationRepository()
        repository.create(medication(MED_1), regimen(REGIMEN_1, MED_1))
        local.database.drainUploadQueue()

        val result = repository.stop(medication(MED_1), regimen(REGIMEN_1, MED_1), LocalDate.of(2026, 10, 31))

        assertInstanceOf(MedsResult.Failure::class.java, result)
        assertNull(local.database.getNextCrudTransaction())
    }

    // --- Dose log -----------------------------------------------------------

    @Test
    fun `confirming a time group queues one entry per slot, with the giver and quantity`() = runBlocking {
        seedCircleAndPatient()
        val medications = medicationRepository()
        medications.create(medication(MED_1), regimen(REGIMEN_1, MED_1))
        medications.create(medication(MED_2), regimen(REGIMEN_2, MED_2))
        local.database.drainUploadQueue()

        val group = group(slotsFor(REGIMEN_1 to MED_1, REGIMEN_2 to MED_2))
        assertEquals(2, group.slotCount)
        val repository = doseLogRepository()

        val result = repository.confirm(group)

        assertEquals(MedsResult.Success(Unit), result)
        val transaction = local.database.nextTransaction()
        assertEquals(
            listOf(UpdateType.PUT to "dose_log" to ENTRY_1, UpdateType.PUT to "dose_log" to ENTRY_2),
            transaction.crud.map { it.op to it.table to it.id },
        )
        // One tap, but storage keeps one row per medication so a single refusal is
        // still a separate entry.
        val first = transaction.crud.first().values()
        assertEquals(JsonPrimitive(USER), first["confirmed_by"])
        assertEquals(JsonPrimitive(USER), first["given_by_user_id"])
        assertEquals(JsonPrimitive("taken"), first["outcome"])
        assertEquals(JsonPrimitive("2026-11-19 08:00:00"), first["scheduled_local"])
        assertEquals(JsonPrimitive("0.5"), first["actual_quantity"])
        assertEquals(JsonPrimitive(NOW.toString()), first["actual_at"])
    }

    @Test
    fun `skipping with a reason writes a skipped entry and no quantity`() = runBlocking {
        seedCircleAndPatient()
        val medications = medicationRepository()
        medications.create(medication(MED_1), regimen(REGIMEN_1, MED_1))
        local.database.drainUploadQueue()

        val group = group(slotsFor(REGIMEN_1 to MED_1))
        val repository = doseLogRepository()

        repository.confirm(group, skipped = mapOf(group.slots.single().slotId to SkipReason.REFUSED))

        val entry = local.database.nextTransaction().crud.single().values()
        assertEquals(JsonPrimitive("skipped"), entry["outcome"])
        assertEquals(JsonPrimitive("refused"), entry["skip_reason"])
        assertNull(entry["actual_quantity"], "A skipped dose has no quantity")
    }

    @Test
    fun `a dose given by someone outside the circle keeps only the name`() = runBlocking {
        seedCircleAndPatient()
        val medications = medicationRepository()
        medications.create(medication(MED_1), regimen(REGIMEN_1, MED_1))
        local.database.drainUploadQueue()

        val group = group(slotsFor(REGIMEN_1 to MED_1))
        doseLogRepository().confirm(group, givenBy = GivenBy.SomeoneElse("Neighbour"))

        val entry = local.database.nextTransaction().crud.single().values()
        assertNull(entry["given_by_user_id"])
        assertEquals(JsonPrimitive(1L), entry["given_by_other"])
        assertEquals(JsonPrimitive("Neighbour"), entry["given_by_name"])
    }

    @Test
    fun `a correction is a new entry that points at the original`() = runBlocking {
        seedCircleAndPatient()
        val medications = medicationRepository()
        medications.create(medication(MED_1), regimen(REGIMEN_1, MED_1))
        local.database.drainUploadQueue()

        // The original is ENTRY_1; the correction must be a fresh id, or the
        // domain rule would read it as a correction of itself (DOSE_CORRECTION).
        val repository = doseLogRepository(newId = { ENTRY_2 })
        val original = DoseLogEntry(
            id = DoseLogEntryId(ENTRY_1),
            regimenId = RegimenId(REGIMEN_1),
            medicationId = MedicationId(MED_1),
            scheduledLocal = slotAt0800(),
            outcome = DoseOutcome.TAKEN,
            actualAt = NOW,
            confirmedBy = USER,
        )

        val result = repository.correct(
            entry = original,
            outcome = DoseOutcome.SKIPPED,
            skipReason = SkipReason.REFUSED,
        )

        assertEquals(MedsResult.Success(DoseLogEntryId(ENTRY_2)), result)
        val crud = local.database.nextTransaction().crud.single()
        val entry = crud.values()
        assertEquals(JsonPrimitive(ENTRY_1), entry["corrects_entry_id"])
        assertEquals(JsonPrimitive("2026-11-19 08:00:00"), entry["scheduled_local"])
        assertEquals(JsonPrimitive("skipped"), entry["outcome"])
        // Nothing is edited or deleted: the original row stays.
        assertEquals(ENTRY_2, crud.id, "The correction is a new row")
    }

    // --- Roles --------------------------------------------------------------

    @Test
    fun `a viewer is refused and nothing is queued`() = runBlocking {
        seedCircleAndPatient(role = MedsLocalDatabase.VIEWER)
        val repository = medicationRepository()

        val create = repository.create(medication(MED_1), regimen(REGIMEN_1, MED_1))

        assertEquals(MedsResult.Failure(MedsError.NotAllowed), create)
        assertNull(local.database.getNextCrudTransaction(), "A refused write must not be queued")
    }

    @Test
    fun `a viewer cannot confirm a dose either`() = runBlocking {
        seedCircleAndPatient(role = MedsLocalDatabase.CAREGIVER)
        medicationRepository().create(medication(MED_1), regimen(REGIMEN_1, MED_1))
        local.database.drainUploadQueue()

        // The phone believes the user is a caregiver (a role change that has not
        // synced yet); the local check still refuses nothing it may not write.
        val group = group(slotsFor(REGIMEN_1 to MED_1))
        local.database.execute(
            "UPDATE memberships SET role = ? WHERE user_id = ?",
            listOf(MedsLocalDatabase.VIEWER, USER),
        )
        local.database.drainUploadQueue()

        assertEquals(MedsResult.Failure(MedsError.NotAllowed), doseLogRepository().confirm(group))
        assertNull(local.database.getNextCrudTransaction())
    }

    // --- Doses today --------------------------------------------------------

    @Test
    fun `doses today derives due, overdue and taken from the injected clock`() = runBlocking {
        seedCircleAndPatient()
        // 09:00 is past the 30-minute escalation delay (OVERDUE until 11:00),
        // 09:45 is inside it (DUE), and 08:00 already has a taken entry (TAKEN).
        local.database.execute(
            INSERT_MEDICATION,
            listOf(MED_1, PATIENT_1, "Metformin", "tablet", "tablet", 0L, 7L),
        )
        local.database.execute(
            INSERT_REGIMEN,
            listOf(REGIMEN_1, MED_1, "daily", "2026-11-01", "Europe/Lisbon",
                """[{"time":"08:00","quantity":1},{"time":"09:00","quantity":0.5},{"time":"09:45","quantity":0.125}]"""),
        )
        local.database.execute(
            INSERT_DOSE_LOG,
            listOf(ENTRY_1, REGIMEN_1, MED_1, "2026-11-19 08:00:00", "taken", null,
                NOW.toString(), "1.000", USER),
        )
        local.database.drainUploadQueue()

        val groups = withTimeout(TIMEOUT_MS) {
            doseLogRepository().dosesToday.first { it.isNotEmpty() }
        }

        assertEquals(
            mapOf(
                LocalTime.of(8, 0) to DoseState.TAKEN,
                LocalTime.of(9, 0) to DoseState.OVERDUE,
                LocalTime.of(9, 45) to DoseState.DUE,
            ),
            groups.associate { it.scheduledLocal.toLocalTime() to it.statuses.single().state },
        )
        assertEquals(BigDecimal("0.125"), groups.single { it.scheduledLocal.toLocalTime() == LocalTime.of(9, 45) }.slots.single().quantity)
    }

    @Test
    fun `doses today skips a stopped medication and an archived patient`() = runBlocking {
        seedCircleAndPatient()
        local.database.execute(
            INSERT_MEDICATION,
            listOf(MED_1, PATIENT_1, "Metformin", "tablet", "tablet", 0L, 7L),
        )
        local.database.execute(
            INSERT_REGIMEN,
            listOf(REGIMEN_1, MED_1, "daily", "2026-11-01", "Europe/Lisbon",
                """[{"time":"19:00","quantity":1}]"""),
        )
        local.database.execute(
            "UPDATE medications SET stopped_at = ? WHERE id = ?",
            listOf(NOW.toString(), MED_1),
        )
        local.database.drainUploadQueue()

        val groups = withTimeout(TIMEOUT_MS) {
            doseLogRepository().dosesToday.first()
        }

        assertTrue(groups.isEmpty(), "A stopped medication has no doses today")
    }

    // --- Helpers ------------------------------------------------------------

    private suspend fun seedCircleAndPatient(role: String = MedsLocalDatabase.OWNER) {
        local.seedOwner(role = role)
        local.seedPatient(PATIENT_1)
        local.database.drainUploadQueue()
    }

    private fun medication(id: String, name: String = "Metformin") = Medication(
        id = MedicationId(id),
        patientId = PatientId(PATIENT_1),
        name = name,
        strength = "500 mg",
        form = MedicationForm.TABLET,
        stockUnit = "tablet",
        requiresPrescription = true,
    )

    private fun regimen(
        id: String,
        medicationId: String,
        times: List<DoseTime> = defaultTimes(),
    ) = Regimen(
        id = RegimenId(id),
        medicationId = MedicationId(medicationId),
        scheduleType = ScheduleType.DAILY,
        startDate = LocalDate.of(2026, 11, 1),
        timeZone = ZoneId.of("Europe/Lisbon"),
        times = times,
    )

    private fun defaultTimes() = listOf(
        DoseTime(LocalTime.of(21, 0), BigDecimal("1")),
        DoseTime(LocalTime.of(8, 0), BigDecimal("0.5")),
    )

    /** The 08:00 slots of [regimens], grouped into one confirmation. */
    private fun slotsFor(vararg regimens: Pair<String, String>) = DoseSlotRules.doseSlots(
        regimens.map { (regimenId, medicationId) -> regimen(regimenId, medicationId) },
        TODAY,
        TODAY,
    )

    private fun group(slots: List<io.github.lgpy.familycare.feature.medications.domain.DoseSlot>) =
        groupTimeSlots(slots) { PatientId(PATIENT_1) }
            .single { it.localTime == LocalTime.of(8, 0) }

    private fun medicationRepository(newId: () -> String = { REGIMEN_1 }): MedicationRepository =
        PowerSyncMedicationRepository(
            database = local.database,
            authState = local.authState,
            now = { NOW },
            newId = newId,
        )

    private var entryIds = listOf(ENTRY_1, ENTRY_2).iterator()

    private fun doseLogRepository(newId: () -> String = { entryIds.next() }): DoseLogRepository =
        PowerSyncDoseLogRepository(
            database = local.database,
            authState = local.authState,
            now = { NOW },
            ticks = flowOf(NOW),
            newId = newId,
        )

    private fun slotAt0800() = TODAY.atTime(LocalTime.of(8, 0))

    private fun CrudEntry.values(): JsonObject = opData?.jsonValues ?: JsonObject(emptyMap())

    private companion object {
        const val USER = MedsLocalDatabase.DEFAULT_USER_ID
        const val PATIENT_1 = "33333333-3333-4333-8333-333333333333"
        const val MED_1 = "44444444-4444-4444-8444-444444444444"
        const val MED_2 = "44444444-4444-4444-8444-444444444445"
        const val REGIMEN_1 = "55555555-5555-4555-8555-555555555555"
        const val REGIMEN_2 = "55555555-5555-4555-8555-555555555556"
        const val ENTRY_1 = "66666666-6666-4666-8666-666666666666"
        const val ENTRY_2 = "66666666-6666-4666-8666-666666666667"

        val TODAY: LocalDate = LocalDate.of(2026, 11, 19)
        /** 10:00 in Lisbon (WET in November). */
        val NOW: Instant = Instant.parse("2026-11-19T10:00:00Z")
        const val TIMEOUT_MS = 15_000L

        const val INSERT_MEDICATION = """
            INSERT INTO medications (
                id, patient_id, name, form, stock_unit, requires_prescription,
                low_stock_threshold_days, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, '2026-11-01T00:00:00.000Z', '2026-11-01T00:00:00.000Z')
        """

        const val INSERT_REGIMEN = """
            INSERT INTO regimens (
                id, medication_id, schedule_type, start_date, time_zone, times, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, '2026-11-01T00:00:00.000Z')
        """

        const val INSERT_DOSE_LOG = """
            INSERT INTO dose_log (
                id, regimen_id, medication_id, scheduled_local, outcome, skip_reason,
                actual_at, actual_quantity, confirmed_by, given_by_other
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """
    }
}
