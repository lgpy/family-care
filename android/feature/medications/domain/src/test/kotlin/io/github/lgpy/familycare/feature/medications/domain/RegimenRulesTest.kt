package io.github.lgpy.familycare.feature.medications.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The regimen rules: schedule validation, the weekly mask, every-N-days
 * anchoring, versioning (D9) and the no-overlap rule.
 */
class RegimenRulesTest {

    private val day = { text: String -> LocalDate.parse(text) }

    // --- Validation --------------------------------------------------------

    @Test
    fun `a daily regimen with one time is valid`() {
        assertTrue(Fixtures.regimen().validate().isValid)
    }

    @Test
    fun `weekdays requires a mask in 1 to 127`() {
        val missing = Fixtures.regimen(scheduleType = ScheduleType.WEEKDAYS, weekdays = null)
        assertEquals(
            setOf(MedicationField.REGIMEN_WEEKDAYS),
            missing.validate().errors,
        )

        for (mask in listOf(0, 128, -1)) {
            assertEquals(
                setOf(MedicationField.REGIMEN_WEEKDAYS),
                Fixtures.regimen(scheduleType = ScheduleType.WEEKDAYS, weekdays = mask).validate().errors,
                "mask $mask",
            )
        }

        assertTrue(
            Fixtures.regimen(scheduleType = ScheduleType.WEEKDAYS, weekdays = WeekdayMask.ALL)
                .validate().isValid,
        )
        assertTrue(
            Fixtures.regimen(scheduleType = ScheduleType.WEEKDAYS, weekdays = WeekdayMask.MONDAY)
                .validate().isValid,
        )
    }

    @Test
    fun `other schedules may not carry a weekday mask`() {
        assertEquals(
            setOf(MedicationField.REGIMEN_WEEKDAYS),
            Fixtures.regimen(scheduleType = ScheduleType.DAILY, weekdays = 1)
                .validate().errors,
        )
        assertEquals(
            setOf(MedicationField.REGIMEN_WEEKDAYS),
            Fixtures.regimen(
                scheduleType = ScheduleType.EVERY_N_DAYS,
                intervalDays = 3,
                weekdays = 1,
            ).validate().errors,
        )
    }

    @Test
    fun `every N days requires an interval between 2 and 365`() {
        val missing = Fixtures.regimen(
            scheduleType = ScheduleType.EVERY_N_DAYS,
            intervalDays = null,
        )
        assertEquals(setOf(MedicationField.REGIMEN_INTERVAL_DAYS), missing.validate().errors)

        for (interval in listOf(1, 0, 366, -3)) {
            assertEquals(
                setOf(MedicationField.REGIMEN_INTERVAL_DAYS),
                Fixtures.regimen(
                    scheduleType = ScheduleType.EVERY_N_DAYS,
                    intervalDays = interval,
                ).validate().errors,
                "interval $interval",
            )
        }
        assertTrue(
            Fixtures.regimen(scheduleType = ScheduleType.EVERY_N_DAYS, intervalDays = 2).validate().isValid,
        )
        assertTrue(
            Fixtures.regimen(scheduleType = ScheduleType.EVERY_N_DAYS, intervalDays = 365).validate().isValid,
        )
    }

    @Test
    fun `other schedules may not carry an interval`() {
        assertEquals(
            setOf(MedicationField.REGIMEN_INTERVAL_DAYS),
            Fixtures.regimen(scheduleType = ScheduleType.DAILY, intervalDays = 3)
                .validate().errors,
        )
    }

    @Test
    fun `the end date may not be before the start date`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-11-10"),
            endDate = day("2026-11-09"),
        )

        assertEquals(setOf(MedicationField.REGIMEN_END_DATE), regimen.validate().errors)
    }

    @Test
    fun `a one-day regimen is valid`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-11-10"),
            endDate = day("2026-11-10"),
        )

        assertTrue(regimen.validate().isValid)
    }

    @Test
    fun `at least one time and at most twelve are required`() {
        assertEquals(
            setOf(MedicationField.REGIMEN_TIMES),
            Fixtures.regimen(times = emptyList()).validate().errors,
        )

        val thirteen = (1..13).map { Fixtures.time("%02d:00".format(it), 1) }
        assertEquals(
            setOf(MedicationField.REGIMEN_TIMES),
            Fixtures.regimen(times = thirteen).validate().errors,
        )

        val twelve = (1..12).map { Fixtures.time("%02d:00".format(it), 1) }
        assertTrue(Fixtures.regimen(times = twelve).validate().isValid)
    }

    @Test
    fun `duplicate times are rejected because the quantity would be ambiguous`() {
        val regimen = Fixtures.regimen(
            times = listOf(Fixtures.time("08:00", 1), Fixtures.time("08:00", "0.5")),
        )

        assertEquals(setOf(MedicationField.REGIMEN_TIMES), regimen.validate().errors)
    }

    @Test
    fun `times are whole minutes`() {
        val withSeconds = Fixtures.regimen(
            times = listOf(DoseTime(LocalTime.parse("08:00:30"), BigDecimal.ONE)),
        )

        assertEquals(setOf(MedicationField.REGIMEN_TIMES), withSeconds.validate().errors)
    }

    @Test
    fun `quantities are greater than zero, at most 100 and at most three decimals`() {
        listOf("0", "-1", "100.001", "101", "0.0001").forEach { quantity ->
            assertEquals(
                setOf(MedicationField.REGIMEN_TIMES),
                Fixtures.regimen(times = listOf(Fixtures.time("08:00", quantity)))
                    .validate().errors,
                "quantity $quantity",
            )
        }

        listOf("0.001", "0.5", "1", "100", "100.000").forEach { quantity ->
            assertTrue(
                Fixtures.regimen(times = listOf(Fixtures.time("08:00", quantity)))
                    .validate().isValid,
                "quantity $quantity",
            )
        }
    }

    @Test
    fun `validation does not care about the order of the times`() {
        val unsorted = Fixtures.regimen(
            times = listOf(Fixtures.time("21:00", 1), Fixtures.time("08:00", 1)),
        )

        assertTrue(unsorted.validate().isValid)
        assertEquals(
            listOf(LocalTime.parse("08:00"), LocalTime.parse("21:00")),
            unsorted.normalized().times.map { it.time },
        )
    }

    // --- Occurs on ---------------------------------------------------------

    @Test
    fun `a daily regimen occurs every day inside its bounds`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-11-16"),
            endDate = day("2026-11-18"),
        )

        assertFalse(regimen.occursOn(day("2026-11-15")))
        assertTrue(regimen.occursOn(day("2026-11-16")))
        assertTrue(regimen.occursOn(day("2026-11-17")))
        assertTrue(regimen.occursOn(day("2026-11-18")))
        assertFalse(regimen.occursOn(day("2026-11-19")))
    }

    @Test
    fun `the weekly mask selects ISO weekdays`() {
        val monWedFri = Fixtures.regimen(
            scheduleType = ScheduleType.WEEKDAYS,
            weekdays = WeekdayMask.MONDAY or WeekdayMask.WEDNESDAY or WeekdayMask.FRIDAY,
            startDate = day("2026-11-01"),
        )

        val expected = setOf(
            LocalDate.parse("2026-11-16"), // Monday
            LocalDate.parse("2026-11-18"), // Wednesday
            LocalDate.parse("2026-11-20"), // Friday
        )
        (16..22).forEach { dayOfMonth ->
            val date = LocalDate.parse("2026-11-%02d".format(dayOfMonth))
            assertEquals(date in expected, monWedFri.occursOn(date), "$date")
        }
    }

    @Test
    fun `the weekly mask maps Monday to 1 and Sunday to 64`() {
        assertEquals(1, WeekdayMask.MONDAY)
        assertEquals(2, WeekdayMask.TUESDAY)
        assertEquals(4, WeekdayMask.WEDNESDAY)
        assertEquals(8, WeekdayMask.THURSDAY)
        assertEquals(16, WeekdayMask.FRIDAY)
        assertEquals(32, WeekdayMask.SATURDAY)
        assertEquals(64, WeekdayMask.SUNDAY)
        assertEquals(127, WeekdayMask.ALL)
        DayOfWeek.entries.forEach { day ->
            assertEquals(1 shl (day.value - 1), WeekdayMask.bit(day))
            assertTrue(WeekdayMask.includes(WeekdayMask.ALL, day))
        }
        assertEquals(
            listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY,
            ),
            WeekdayMask.days(WeekdayMask.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
        )
    }

    @Test
    fun `every N days is anchored on the start date, not the range`() {
        val regimen = Fixtures.regimen(
            scheduleType = ScheduleType.EVERY_N_DAYS,
            intervalDays = 3,
            startDate = day("2026-11-10"),
        )

        (9..20).forEach { dayOfMonth ->
            val date = LocalDate.parse("2026-11-%02d".format(dayOfMonth))
            assertEquals((dayOfMonth - 10) % 3 == 0, regimen.occursOn(date), "$date")
        }
    }

    // --- Versioning --------------------------------------------------------

    @Test
    fun `a change ends the current version the day before and starts a new one`() {
        val current = Fixtures.regimen(
            startDate = day("2026-11-01"),
            times = listOf(Fixtures.time("08:00", 1)),
        )

        val outcome = RegimenRules.changeRegimen(
            current = current,
            effectiveFrom = day("2026-11-10"),
            newId = RegimenId("reg-2"),
            times = listOf(Fixtures.time("08:00", 2)),
        )

        val changed = assertInstanceOf(RegimenChange.Changed::class.java, outcome)
        assertEquals(day("2026-11-09"), changed.ended.endDate)
        assertEquals(current.id, changed.ended.id)
        assertEquals(day("2026-11-10"), changed.started.startDate)
        assertEquals(RegimenId("reg-2"), changed.started.id)
        assertEquals(current.medicationId, changed.started.medicationId)
        assertEquals(current.timeZone, changed.started.timeZone)
        assertEquals(BigDecimal("2"), changed.started.times.single().quantity)
        assertNull(changed.started.endDate)
    }

    @Test
    fun `the new version inherits the treatment's end date`() {
        val current = Fixtures.regimen(
            startDate = day("2026-11-01"),
            endDate = day("2026-12-31"),
        )

        val changed = assertInstanceOf(
            RegimenChange.Changed::class.java,
            RegimenRules.changeRegimen(current, day("2026-11-10"), RegimenId("reg-2")),
        )

        assertEquals(day("2026-12-31"), changed.started.endDate)
        assertEquals(day("2026-11-09"), changed.ended.endDate)
    }

    @Test
    fun `a change on the first day is rejected`() {
        val current = Fixtures.regimen(startDate = day("2026-11-01"))

        val outcome = RegimenRules.changeRegimen(
            current,
            day("2026-11-01"),
            RegimenId("reg-2"),
        )

        val rejected = assertInstanceOf(RegimenChange.Rejected::class.java, outcome)
        assertEquals(setOf(MedicationField.REGIMEN_START_DATE), rejected.errors)
    }

    @Test
    fun `a change before the first day is rejected`() {
        val current = Fixtures.regimen(startDate = day("2026-11-01"))

        val outcome = RegimenRules.changeRegimen(
            current,
            day("2026-10-31"),
            RegimenId("reg-2"),
        )

        assertInstanceOf(RegimenChange.Rejected::class.java, outcome)
    }

    @Test
    fun `a change after the version has ended is rejected as a new regimen instead`() {
        val current = Fixtures.regimen(
            startDate = day("2026-11-01"),
            endDate = day("2026-11-20"),
        )

        val outcome = RegimenRules.changeRegimen(
            current,
            day("2026-11-22"),
            RegimenId("reg-2"),
        )

        val rejected = assertInstanceOf(RegimenChange.Rejected::class.java, outcome)
        assertEquals(setOf(MedicationField.REGIMEN_START_DATE), rejected.errors)

        // The day after the last day would leave the new version zero days.
        assertInstanceOf(
            RegimenChange.Rejected::class.java,
            RegimenRules.changeRegimen(current, day("2026-11-21"), RegimenId("reg-2")),
        )

        // The last day itself is still a change: a one-day new version.
        val lastDay = assertInstanceOf(
            RegimenChange.Changed::class.java,
            RegimenRules.changeRegimen(current, day("2026-11-20"), RegimenId("reg-2")),
        )
        assertEquals(day("2026-11-19"), lastDay.ended.endDate)
        assertEquals(day("2026-11-20"), lastDay.started.startDate)
        assertEquals(day("2026-11-20"), lastDay.started.endDate)
    }

    @Test
    fun `a change validates the new schedule`() {
        val current = Fixtures.regimen()

        val outcome = RegimenRules.changeRegimen(
            current = current,
            effectiveFrom = day("2026-11-10"),
            newId = RegimenId("reg-2"),
            times = listOf(Fixtures.time("08:00", 1), Fixtures.time("08:00", 2)),
        )

        val rejected = assertInstanceOf(RegimenChange.Rejected::class.java, outcome)
        assertEquals(setOf(MedicationField.REGIMEN_TIMES), rejected.errors)
    }

    @Test
    fun `a change can move the schedule type`() {
        val current = Fixtures.regimen()

        val changed = assertInstanceOf(
            RegimenChange.Changed::class.java,
            RegimenRules.changeRegimen(
                current = current,
                effectiveFrom = day("2026-11-10"),
                newId = RegimenId("reg-2"),
                scheduleType = ScheduleType.WEEKDAYS,
                weekdays = WeekdayMask.MONDAY,
                intervalDays = null,
            ),
        )

        assertEquals(ScheduleType.WEEKDAYS, changed.started.scheduleType)
        assertEquals(WeekdayMask.MONDAY, changed.started.weekdays)
    }

    @Test
    fun `a change needs a new regimen id`() {
        val current = Fixtures.regimen()

        assertThrows(IllegalArgumentException::class.java) {
            RegimenRules.changeRegimen(current, day("2026-11-10"), current.id)
        }
    }

    @Test
    fun `stopping sets the last day`() {
        val current = Fixtures.regimen(startDate = day("2026-11-01"))

        val stopped = assertInstanceOf(
            RegimenStop.Stopped::class.java,
            RegimenRules.stop(current, day("2026-11-30")),
        )

        assertEquals(day("2026-11-30"), stopped.regimen.endDate)
        assertTrue(stopped.regimen.validate().isValid)
    }

    @Test
    fun `stopping may shorten but never extend a treatment`() {
        val current = Fixtures.regimen(
            startDate = day("2026-11-01"),
            endDate = day("2026-11-30"),
        )

        val shortened = assertInstanceOf(
            RegimenStop.Stopped::class.java,
            RegimenRules.stop(current, day("2026-11-15")),
        )
        assertEquals(day("2026-11-15"), shortened.regimen.endDate)

        val extended = assertInstanceOf(
            RegimenStop.Rejected::class.java,
            RegimenRules.stop(current, day("2026-12-01")),
        )
        assertEquals(setOf(MedicationField.REGIMEN_END_DATE), extended.errors)
    }

    @Test
    fun `stopping before the first day is rejected`() {
        val current = Fixtures.regimen(startDate = day("2026-11-01"))

        val outcome = RegimenRules.stop(current, day("2026-10-31"))

        assertInstanceOf(RegimenStop.Rejected::class.java, outcome)
    }

    @Test
    fun `the last day may be the first day`() {
        val current = Fixtures.regimen(startDate = day("2026-11-01"))

        val stopped = assertInstanceOf(
            RegimenStop.Stopped::class.java,
            RegimenRules.stop(current, day("2026-11-01")),
        )

        assertEquals(day("2026-11-01"), stopped.regimen.endDate)
    }

    // --- Overlaps ----------------------------------------------------------

    @Test
    fun `adjacent versions do not overlap`() {
        val first = Fixtures.regimen(
            id = RegimenId("reg-1"),
            startDate = day("2026-11-01"),
            endDate = day("2026-11-09"),
        )
        val second = Fixtures.regimen(
            id = RegimenId("reg-2"),
            startDate = day("2026-11-10"),
        )

        assertFalse(RegimenRules.hasOverlaps(listOf(first, second)))
        assertTrue(RegimenRules.validateNoOverlap(listOf(first, second)).isValid)
    }

    @Test
    fun `an inclusive boundary that shares a day overlaps`() {
        val first = Fixtures.regimen(
            id = RegimenId("reg-1"),
            startDate = day("2026-11-01"),
            endDate = day("2026-11-10"),
        )
        val second = Fixtures.regimen(
            id = RegimenId("reg-2"),
            startDate = day("2026-11-10"),
        )

        val overlaps = RegimenRules.findOverlaps(listOf(first, second))

        assertEquals(1, overlaps.size)
        assertEquals(RegimenId("reg-1"), overlaps.single().earlier.id)
        assertEquals(
            setOf(MedicationField.REGIMEN_OVERLAP),
            RegimenRules.validateNoOverlap(listOf(first, second)).errors,
        )
    }

    @Test
    fun `an open ended version overlaps every later version of the same medication`() {
        val open = Fixtures.regimen(
            id = RegimenId("reg-1"),
            startDate = day("2026-11-01"),
        )
        val later = Fixtures.regimen(
            id = RegimenId("reg-2"),
            startDate = day("2027-01-01"),
        )

        assertEquals(1, RegimenRules.findOverlaps(listOf(open, later)).size)
    }

    @Test
    fun `versions of different medications never overlap`() {
        val first = Fixtures.regimen(
            id = RegimenId("reg-1"),
            medicationId = MedicationId("med-1"),
        )
        val second = Fixtures.regimen(
            id = RegimenId("reg-2"),
            medicationId = MedicationId("med-2"),
        )

        assertFalse(RegimenRules.hasOverlaps(listOf(first, second)))
    }

    @Test
    fun `two versions starting on the same day overlap`() {
        val first = Fixtures.regimen(id = RegimenId("reg-1"))
        val second = Fixtures.regimen(id = RegimenId("reg-2"))

        assertEquals(1, RegimenRules.findOverlaps(listOf(first, second)).size)
    }

    @Test
    fun `schedule types round-trip through their server values`() {
        ScheduleType.entries.forEach { type ->
            assertEquals(type, ScheduleType.fromServer(type.serverValue))
        }
        assertNull(ScheduleType.fromServer("every_n_hours"))
        assertEquals("every_n_days", ScheduleType.EVERY_N_DAYS.serverValue)
    }

    @Test
    fun `the zone is part of the regimen version`() {
        val current = Fixtures.regimen(timeZone = ZoneId.of("Europe/Lisbon"))

        val changed = assertInstanceOf(
            RegimenChange.Changed::class.java,
            RegimenRules.changeRegimen(
                current = current,
                effectiveFrom = day("2026-11-10"),
                newId = RegimenId("reg-2"),
                timeZone = ZoneId.of("America/Sao_Paulo"),
            ),
        )

        assertEquals(ZoneId.of("Europe/Lisbon"), changed.ended.timeZone)
        assertEquals(ZoneId.of("America/Sao_Paulo"), changed.started.timeZone)
    }
}
