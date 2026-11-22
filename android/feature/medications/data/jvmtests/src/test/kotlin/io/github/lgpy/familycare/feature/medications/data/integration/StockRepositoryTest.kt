package io.github.lgpy.familycare.feature.medications.data.integration

import com.powersync.db.crud.CrudEntry
import com.powersync.db.crud.UpdateType
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.PowerSyncDoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.PowerSyncMedicationRepository
import io.github.lgpy.familycare.feature.medications.data.PowerSyncStockRepository
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.domain.DoseSlotRules
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.domain.StockMovementId
import io.github.lgpy.familycare.feature.medications.domain.StockMovementKind
import io.github.lgpy.familycare.feature.medications.domain.groupTimeSlots
import java.math.BigDecimal
import java.time.Clock
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stock ledger (D28) against a **real PowerSync database** built from the
 * merged schema.
 *
 * What this catches that a domain test cannot: `stock_movements` is a PowerSync
 * view with `INSTEAD OF` triggers, so the INSERT, the column names and the
 * decimal text form are only proven here; and the summary has to react to a
 * dose confirmed through the real dose-log repository, with no second ledger.
 */
class StockRepositoryTest {

    private val local = MedsLocalDatabase(MedsLocalDatabase.testDirectory("stock-repo"))

    private var now: Instant = NOW

    @AfterEach
    fun close() {
        local.close()
    }

    // --- The ledger ---------------------------------------------------------

    @Test
    fun `counts, refills and discards round-trip through the local database`() = runBlocking {
        seedExistingMedication()
        val repository = stockRepository()

        assertEquals(
            MedsResult.Success(StockMovementId(MOVEMENT_1)),
            repository.recordCount(MedicationId(MED_1), BigDecimal("30"), note = "opening"),
        )
        val transaction = local.database.nextTransaction()
        assertEquals(
            UpdateType.PUT to "stock_movements" to MOVEMENT_1,
            transaction.crud.single().let { it.op to it.table to it.id },
        )
        val count = transaction.crud.single().values()
        assertEquals(JsonPrimitive(MED_1), count["medication_id"])
        assertEquals(JsonPrimitive("count"), count["kind"])
        assertEquals(JsonPrimitive("30"), count["quantity"])
        assertEquals(JsonPrimitive("opening"), count["reason"])
        assertEquals(JsonPrimitive(NOW.toString()), count["occurred_at"])
        // The server stamps the creator, exactly as for the dose log: never uploaded.
        assertNull(count["created_by"])
        local.database.drainUploadQueue()

        // Movements at the count's own instant are inside the count (README rule),
        // so the refill and the discard happen later, as they would on a phone.
        now = NOW.plusSeconds(60)
        repository.recordRefill(MedicationId(MED_1), BigDecimal("20"))
        local.database.drainUploadQueue()
        now = NOW.plusSeconds(120)
        repository.recordDiscard(MedicationId(MED_1), BigDecimal("5.5"), reason = "expired")
        local.database.drainUploadQueue()

        val movements = withTimeout(TIMEOUT_MS) {
            repository.observeMovements(MedicationId(MED_1)).first { it.size == 3 }
        }
        assertEquals(
            listOf(
                StockMovementKind.COUNT to BigDecimal("30"),
                StockMovementKind.REFILL to BigDecimal("20"),
                StockMovementKind.DISCARD to BigDecimal("5.5"),
            ),
            movements.map { it.kind to it.quantity },
        )

        val overview = withTimeout(TIMEOUT_MS) {
            repository.observeOverview(MedicationId(MED_1)).first { it != null }
        }!!
        assertEquals(0, overview.summary.stock.compareTo(BigDecimal("44.5")))
    }

    @Test
    fun `an invalid movement is refused before anything is written`() = runBlocking {
        seedExistingMedication()
        val repository = stockRepository()

        assertEquals(
            MedsResult.Failure(MedsError.Invalid(setOf(MedicationField.STOCK_REASON))),
            repository.recordDiscard(MedicationId(MED_1), BigDecimal("2"), reason = "  "),
        )
        assertEquals(
            MedsResult.Failure(MedsError.Invalid(setOf(MedicationField.STOCK_QUANTITY))),
            repository.recordCount(MedicationId(MED_1), BigDecimal("-1")),
        )
        assertNull(local.database.getNextCrudTransaction(), "Nothing invalid may be queued")
    }

    @Test
    fun `a viewer cannot record stock`() = runBlocking {
        seedExistingMedication(role = MedsLocalDatabase.VIEWER)
        val repository = stockRepository()

        assertEquals(
            MedsResult.Failure(MedsError.NotAllowed),
            repository.recordRefill(MedicationId(MED_1), BigDecimal("10")),
        )
        assertNull(local.database.getNextCrudTransaction())
    }

    // --- The derived summary ------------------------------------------------

    @Test
    fun `confirming a dose lowers the stock without a second ledger`() = runBlocking {
        seedCircleAndPatient()
        val medications = medicationRepository()
        medications.create(medication(MED_1), regimen(REGIMEN_1, MED_1), openingQuantity = BigDecimal("10"))
        local.database.drainUploadQueue()

        val stock = stockRepository()
        val before = withTimeout(TIMEOUT_MS) {
            stock.observeOverview(MedicationId(MED_1)).first { it != null }
        }!!
        assertEquals(0, before.summary.stock.compareTo(BigDecimal("10")))
        assertEquals(BigDecimal.ONE, before.summary.dailyConsumption)
        assertEquals(10, before.summary.daysRemaining)
        assertFalse(before.summary.low)

        // The 08:00 dose of today, confirmed ten minutes after the count.
        val slots = DoseSlotRules.doseSlots(listOf(regimen(REGIMEN_1, MED_1)), TODAY, TODAY)
        val group = groupTimeSlots(slots) { PatientId(PATIENT_1) }.single()
        now = NOW.plusSeconds(600)
        assertEquals(MedsResult.Success(Unit), doseLogRepository().confirm(group))
        local.database.drainUploadQueue()

        val after = withTimeout(TIMEOUT_MS) {
            stock.observeOverview(MedicationId(MED_1)).first {
                it != null && it.summary.stock.compareTo(BigDecimal("9")) == 0
            }
        }!!
        assertEquals(9, after.summary.daysRemaining)
        assertEquals(1, after.movements.size, "The ledger has only the opening count")
    }

    @Test
    fun `an opening count is written with the medication and its regimen, in one transaction`() =
        runBlocking {
            local.seedOwner()
            local.seedPatient(PATIENT_1)
            local.database.drainUploadQueue()

            val repository = medicationRepository()
            val result = repository.create(
                medication(MED_1),
                regimen(REGIMEN_1, MED_1),
                openingQuantity = BigDecimal("30"),
            )

            assertEquals(MedsResult.Success(MedicationId(MED_1)), result)
            val transaction = local.database.nextTransaction()
            assertEquals(
                listOf(
                    UpdateType.PUT to "medications" to MED_1,
                    UpdateType.PUT to "regimens" to REGIMEN_1,
                    UpdateType.PUT to "stock_movements" to MOVEMENT_1,
                ),
                transaction.crud.map { it.op to it.table to it.id },
            )
            val count = transaction.crud.last().values()
            assertEquals(JsonPrimitive("count"), count["kind"])
            assertEquals(JsonPrimitive("30"), count["quantity"])
            transaction.complete(null)
            assertNull(local.database.getNextCrudTransaction(), "One action, one transaction")
        }

    @Test
    fun `an invalid opening quantity creates nothing`() = runBlocking {
        local.seedOwner()
        local.seedPatient(PATIENT_1)
        local.database.drainUploadQueue()

        val result = medicationRepository().create(
            medication(MED_1),
            regimen(REGIMEN_1, MED_1),
            openingQuantity = BigDecimal("-1"),
        )

        assertEquals(
            MedsResult.Failure(MedsError.Invalid(setOf(MedicationField.STOCK_QUANTITY))),
            result,
        )
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `stopped medications stay out of the shopping overviews`() = runBlocking {
        seedCircleAndPatient()
        val medications = medicationRepository()
        medications.create(medication(MED_1), regimen(REGIMEN_1, MED_1), openingQuantity = BigDecimal("0"))
        local.database.drainUploadQueue()

        val low = withTimeout(TIMEOUT_MS) {
            stockRepository().overviews.first { it.isNotEmpty() }
        }
        assertTrue(low.single().low, "A count of zero with an active daily schedule is low")

        // A last day that is already over stops the medication now; a last day of
        // today would only stop it at midnight and it would stay low until then.
        medications.stop(medication(MED_1), regimen(REGIMEN_1, MED_1), TODAY.minusDays(1))
        local.database.drainUploadQueue()

        val afterStop = withTimeout(TIMEOUT_MS) {
            stockRepository().overviews.first { it.isEmpty() }
        }
        assertTrue(afterStop.isEmpty())
    }

    @Test
    fun `an unknown stock kind is skipped rather than shown as a count`() = runBlocking {
        seedExistingMedication()
        local.database.execute(
            INSERT_STOCK_MOVEMENT,
            listOf(MOVEMENT_1, MED_1, "adjustment", "5", null, NOW.toString()),
        )
        local.database.drainUploadQueue()

        val movements = withTimeout(TIMEOUT_MS) {
            stockRepository().observeMovements(MedicationId(MED_1)).first()
        }
        assertTrue(movements.isEmpty(), "An unknown kind is not silently a count")
    }

    // --- Helpers ------------------------------------------------------------

    private suspend fun seedCircleAndPatient(role: String = MedsLocalDatabase.OWNER) {
        local.seedOwner(role = role)
        local.seedPatient(PATIENT_1)
        local.database.drainUploadQueue()
    }

    /** A medication and its regimen inserted behind the repository's back. */
    private suspend fun seedExistingMedication(role: String = MedsLocalDatabase.OWNER) {
        seedCircleAndPatient(role = role)
        local.database.execute(
            INSERT_MEDICATION,
            listOf(MED_1, PATIENT_1, "Metformin", "tablet", "tablet", 1L, 7L),
        )
        local.database.execute(
            INSERT_REGIMEN,
            listOf(REGIMEN_1, MED_1, "daily", "2026-11-01", "Europe/Lisbon",
                """[{"time":"08:00","quantity":1}]"""),
        )
        local.database.drainUploadQueue()
    }

    private fun medication(id: String) = Medication(
        id = MedicationId(id),
        patientId = PatientId(PATIENT_1),
        name = "Metformin",
        strength = "500 mg",
        form = MedicationForm.TABLET,
        stockUnit = "tablet",
        requiresPrescription = true,
        lowStockThresholdDays = 7,
    )

    private fun regimen(id: String, medicationId: String) = Regimen(
        id = RegimenId(id),
        medicationId = MedicationId(medicationId),
        scheduleType = ScheduleType.DAILY,
        startDate = LocalDate.of(2026, 11, 1),
        timeZone = ZoneId.of("Europe/Lisbon"),
        times = listOf(DoseTime(LocalTime.of(8, 0), BigDecimal.ONE)),
    )

    private var movementIds = listOf(MOVEMENT_1, MOVEMENT_2, MOVEMENT_3).iterator()

    private fun medicationRepository(): MedicationRepository =
        PowerSyncMedicationRepository(
            database = local.database,
            authState = local.authState,
            now = { now },
            // Creation consumes a movement id only when there is an opening
            // balance; the first regimen's id comes from the caller.
            newId = { movementIds.next() },
        )

    private fun doseLogRepository(): DoseLogRepository =
        PowerSyncDoseLogRepository(
            database = local.database,
            authState = local.authState,
            now = { now },
            ticks = flowOf(now),
            newId = { ENTRY_1 },
        )

    private fun stockRepository(): StockRepository = PowerSyncStockRepository(
        database = local.database,
        authState = local.authState,
        medicationRepository = medicationRepository(),
        doseLogRepository = doseLogRepository(),
        clock = testClock,
        ticks = flowOf(now),
        newId = { movementIds.next() },
    )

    /** Reads [now] on every call, so a test can move time between writes. */
    private val testClock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("Europe/Lisbon")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }

    private fun CrudEntry.values(): JsonObject = opData?.jsonValues ?: JsonObject(emptyMap())

    private companion object {
        const val PATIENT_1 = "33333333-3333-4333-8333-333333333333"
        const val MED_1 = "44444444-4444-4444-8444-444444444444"
        const val REGIMEN_1 = "55555555-5555-4555-8555-555555555555"
        const val ENTRY_1 = "66666666-6666-4666-8666-666666666666"
        const val MOVEMENT_1 = "77777777-7777-4777-8777-777777777777"
        const val MOVEMENT_2 = "77777777-7777-4777-8777-777777777778"
        const val MOVEMENT_3 = "77777777-7777-4777-8777-777777777779"

        val TODAY: LocalDate = LocalDate.of(2026, 11, 19)
        /** 10:00 in Lisbon (WET in November). */
        val NOW: Instant = Instant.parse("2026-11-19T10:00:00Z")
        const val TIMEOUT_MS = 15_000L

        const val INSERT_STOCK_MOVEMENT = """
            INSERT INTO stock_movements (id, medication_id, kind, quantity, reason, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """

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
    }
}
