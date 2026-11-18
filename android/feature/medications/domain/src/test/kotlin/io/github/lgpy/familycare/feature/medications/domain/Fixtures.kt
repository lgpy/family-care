package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Small factories so each test states only what it is about. */
internal object Fixtures {

    val circle = CareCircleId("circle-1")
    val patientId = PatientId("patient-1")
    val medicationId = MedicationId("med-1")
    val regimenId = RegimenId("reg-1")
    val zone: ZoneId = ZoneId.of("Europe/Lisbon")

    fun patient(
        id: PatientId = patientId,
        escalationDelayMinutes: Int = 30,
        missedWindowMinutes: Int = 120,
    ): Patient = Patient(
        id = id,
        circleId = circle,
        displayName = "Grandma",
        escalationDelayMinutes = escalationDelayMinutes,
        missedWindowMinutes = missedWindowMinutes,
    )

    fun medication(
        id: MedicationId = medicationId,
        patientId: PatientId = Fixtures.patientId,
        name: String = "Metformin",
        activeIngredient: String? = "Metformin hydrochloride",
        strength: String? = "850 mg",
        form: MedicationForm = MedicationForm.TABLET,
        stockUnit: String = "tablet",
        instructions: String? = "Take with food",
        requiresPrescription: Boolean = true,
        prescriptionValidUntil: LocalDate? = null,
        lowStockThresholdDays: Int = 7,
        reminderPolicy: io.github.lgpy.familycare.core.domain.circle.ReminderPolicy? = null,
        primaryCaregiverId: String? = null,
        stoppedAt: Instant? = null,
    ): Medication = Medication(
        id = id,
        patientId = patientId,
        name = name,
        activeIngredient = activeIngredient,
        strength = strength,
        form = form,
        stockUnit = stockUnit,
        instructions = instructions,
        requiresPrescription = requiresPrescription,
        prescriptionValidUntil = prescriptionValidUntil,
        lowStockThresholdDays = lowStockThresholdDays,
        reminderPolicy = reminderPolicy,
        primaryCaregiverId = primaryCaregiverId,
        stoppedAt = stoppedAt,
    )

    fun time(time: String, quantity: String): DoseTime =
        DoseTime(LocalTime.parse(time), BigDecimal(quantity))

    fun time(time: String, quantity: Int): DoseTime =
        DoseTime(LocalTime.parse(time), BigDecimal(quantity))

    fun times(vararg values: Pair<String, String>): List<DoseTime> =
        values.map { time(it.first, it.second) }

    fun regimen(
        id: RegimenId = regimenId,
        medicationId: MedicationId = Fixtures.medicationId,
        scheduleType: ScheduleType = ScheduleType.DAILY,
        startDate: LocalDate = LocalDate.parse("2026-11-01"),
        timeZone: ZoneId = zone,
        times: List<DoseTime> = listOf(time("08:00", 1)),
        endDate: LocalDate? = null,
        weekdays: Int? = null,
        intervalDays: Int? = null,
    ): Regimen = Regimen(
        id = id,
        medicationId = medicationId,
        scheduleType = scheduleType,
        startDate = startDate,
        timeZone = timeZone,
        times = times,
        endDate = endDate,
        weekdays = weekdays,
        intervalDays = intervalDays,
    )

    /** Lisbon winter (WET, UTC+0) by default, so instants equal wall-clock times. */
    fun slot(
        regimenId: RegimenId = Fixtures.regimenId,
        medicationId: MedicationId = Fixtures.medicationId,
        scheduledLocal: String = "2026-11-16T08:00",
        instant: String = "2026-11-16T08:00:00Z",
        quantity: String = "1",
    ): DoseSlot = DoseSlot(
        regimenId = regimenId,
        medicationId = medicationId,
        scheduledLocal = LocalDateTime.parse(scheduledLocal),
        instant = Instant.parse(instant),
        quantity = BigDecimal(quantity),
    )

    fun entry(
        id: String = "entry-1",
        regimenId: RegimenId = Fixtures.regimenId,
        medicationId: MedicationId = Fixtures.medicationId,
        scheduledLocal: String = "2026-11-16T08:00",
        outcome: DoseOutcome = DoseOutcome.TAKEN,
        skipReason: SkipReason? = null,
        note: String? = null,
        actualAt: String = "2026-11-16T08:04:00Z",
        actualQuantity: String? = "1",
        confirmedBy: String = "user-me",
        givenByUserId: String? = null,
        givenByOther: Boolean = false,
        givenByName: String? = null,
        correctsEntryId: String? = null,
    ): DoseLogEntry = DoseLogEntry(
        id = DoseLogEntryId(id),
        regimenId = regimenId,
        medicationId = medicationId,
        scheduledLocal = LocalDateTime.parse(scheduledLocal),
        outcome = outcome,
        skipReason = skipReason,
        note = note,
        actualAt = Instant.parse(actualAt),
        actualQuantity = actualQuantity?.let(::BigDecimal),
        confirmedBy = confirmedBy,
        givenByUserId = givenByUserId,
        givenByOther = givenByOther,
        givenByName = givenByName,
        correctsEntryId = correctsEntryId?.let(::DoseLogEntryId),
    )

    fun taken(
        id: String = "entry-1",
        actualAt: String = "2026-11-16T08:04:00Z",
    ): DoseLogEntry = entry(id = id, actualAt = actualAt)

    fun skipped(
        id: String = "entry-1",
        reason: SkipReason = SkipReason.REFUSED,
    ): DoseLogEntry = entry(
        id = id,
        outcome = DoseOutcome.SKIPPED,
        skipReason = reason,
        actualQuantity = null,
    )
}
