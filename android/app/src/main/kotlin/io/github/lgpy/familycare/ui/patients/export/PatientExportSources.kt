package io.github.lgpy.familycare.ui.patients.export

import android.content.res.Resources
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.export.ExportBlock
import io.github.lgpy.familycare.core.ui.export.ExportSection
import io.github.lgpy.familycare.core.ui.export.PatientExportSource
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The feature modules' contributions to a patient export (D30).
 *
 * These live in the app because that is the assembly point: each one is built
 * from a module's *public* data API (its repository), and the app registers it
 * as a `PatientExportSource` in Koin exactly as it registers a DashboardCard.
 * No module's internals (SQL, tables) are touched (D5).
 */

/** The medications and their regimens, stock ledger and current stock. */
class MedicationsExportSource(
    private val medicationRepository: MedicationRepository,
    private val stockRepository: StockRepository,
    private val resources: Resources,
) : PatientExportSource {

    override val key: String = "medications"

    override suspend fun json(patientId: PatientId): JsonElement {
        val items = medicationRepository.observe(patientId).first()
        return buildJsonArray {
            items.forEach { item ->
                val overview = stockRepository.observeOverview(item.id).first()
                add(
                    buildJsonObject {
                        val medication = item.medication
                        put("id", medication.id.value)
                        put("patient_id", medication.patientId.value)
                        put("name", medication.name)
                        put("active_ingredient", medication.activeIngredient)
                        put("strength", medication.strength)
                        put("form", medication.form.serverValue)
                        put("stock_unit", medication.stockUnit)
                        put("instructions", medication.instructions)
                        put("requires_prescription", medication.requiresPrescription)
                        put("prescription_valid_until", medication.prescriptionValidUntil?.toString())
                        put("low_stock_threshold_days", medication.lowStockThresholdDays)
                        put("reminder_policy", medication.reminderPolicy?.serverValue)
                        put("primary_caregiver_id", medication.primaryCaregiverId)
                        put("stopped_at", medication.stoppedAt?.toString())
                        put(
                            "regimens",
                            buildJsonArray {
                                item.regimens.forEach { regimen -> add(regimenJson(regimen)) }
                            },
                        )
                        put(
                            "stock_movements",
                            buildJsonArray {
                                overview?.movements?.forEach { movement ->
                                    add(
                                        buildJsonObject {
                                            put("id", movement.id.value)
                                            put("medication_id", movement.medicationId.value)
                                            put("kind", movement.kind.serverValue)
                                            put("quantity", movement.quantity)
                                            put("occurred_at", movement.occurredAt.toString())
                                            put("reason", movement.reason)
                                        },
                                    )
                                }
                            },
                        )
                        put("current_stock", overview?.summary?.displayStock ?: java.math.BigDecimal.ZERO)
                    },
                )
            }
        }
    }

    override suspend fun pdfSections(patient: Patient): List<ExportSection> {
        val items = medicationRepository.observe(patient.id).first()
        val active = items.filter { !it.medication.isStopped }
        val stopped = items.filter { it.medication.isStopped }
        return listOf(
            ExportSection(
                order = 10,
                title = resources.getString(R.string.export_active_medications),
                blocks = if (active.isEmpty()) {
                    listOf(ExportBlock.Paragraph(resources.getString(R.string.export_none)))
                } else {
                    active.map { item -> medicationBlock(item) }
                },
            ),
            ExportSection(
                order = 11,
                title = resources.getString(R.string.export_stopped_medications),
                blocks = if (stopped.isEmpty()) {
                    listOf(ExportBlock.Paragraph(resources.getString(R.string.export_none)))
                } else {
                    stopped.map { item ->
                        val stoppedAt = item.medication.stoppedAt?.toString()?.take(10).orEmpty()
                        ExportBlock.Paragraph(
                            "${item.medication.name}${strength(item.medication)} — " +
                                resources.getString(R.string.export_stopped_on, stoppedAt),
                        )
                    }
                },
            ),
        )
    }

    private suspend fun medicationBlock(item: MedicationWithRegimens): ExportBlock {
        val overview = stockRepository.observeOverview(item.id).first()
        val stock = overview?.summary?.displayStock ?: java.math.BigDecimal.ZERO
        return ExportBlock.Paragraph(
            "${item.medication.name}${strength(item.medication)} — " +
                schedule(item.currentRegimen) + ". " +
                resources.getString(
                    R.string.export_stock,
                    stock.toPlainString(),
                    item.medication.stockUnit,
                ),
        )
    }

    private fun strength(medication: Medication): String =
        medication.strength?.let { " $it" }.orEmpty()

    private fun schedule(regimen: Regimen?): String {
        if (regimen == null) return resources.getString(R.string.export_schedule_none)
        val times = regimen.times.joinToString(", ") { it.time.toString() }
        val kind = when (regimen.scheduleType) {
            io.github.lgpy.familycare.feature.medications.domain.ScheduleType.WEEKDAYS ->
                resources.getString(R.string.export_schedule_weekdays)

            io.github.lgpy.familycare.feature.medications.domain.ScheduleType.EVERY_N_DAYS ->
                resources.getString(R.string.export_schedule_every_n, regimen.intervalDays ?: 0)

            else -> resources.getString(R.string.export_schedule_daily)
        }
        return "$kind: $times"
    }

    private fun regimenJson(regimen: Regimen): JsonElement = buildJsonObject {
        put("id", regimen.id.value)
        put("medication_id", regimen.medicationId.value)
        put("schedule_type", regimen.scheduleType.serverValue)
        put("start_date", regimen.startDate.toString())
        put("end_date", regimen.endDate?.toString())
        put("time_zone", regimen.timeZone.id)
        put("weekdays", regimen.weekdays)
        put("interval_days", regimen.intervalDays)
        put(
            "times",
            buildJsonArray {
                regimen.times.forEach { time ->
                    add(
                        buildJsonObject {
                            put("time", time.time.toString())
                            put("quantity", time.quantity)
                        },
                    )
                }
            },
        )
    }
}

/** The full local dose log of the patient, and its last 90 days for the PDF. */
class DoseLogExportSource(
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val resources: Resources,
    private val today: () -> LocalDate = { LocalDate.now() },
) : PatientExportSource {

    override val key: String = "dose_log"

    override suspend fun json(patientId: PatientId): JsonElement {
        val entries = entries(patientId)
        return buildJsonArray {
            entries.forEach { entry -> add(entryJson(entry)) }
        }
    }

    override suspend fun pdfSections(patient: Patient): List<ExportSection> {
        val items = medicationRepository.observe(patient.id).first()
        val names = items.associate { it.id.value to it.medication.name }
        val from = today().minusDays(90)
        val recent = entries(patient.id).filter { entry ->
            entry.scheduledLocal.toLocalDate() >= from
        }
        return listOf(
            ExportSection(
                order = 30,
                title = resources.getString(R.string.export_dose_log),
                blocks = if (recent.isEmpty()) {
                    listOf(ExportBlock.Paragraph(resources.getString(R.string.export_none)))
                } else {
                    recent.map { entry ->
                        val status = if (entry.outcome.serverValue == "taken") {
                            resources.getString(R.string.export_dose_taken)
                        } else {
                            resources.getString(R.string.export_dose_skipped)
                        }
                        ExportBlock.Paragraph(
                            "${entry.scheduledLocal.toString().replace('T', ' ')} — " +
                                "${names[entry.medicationId.value] ?: ""}: $status",
                        )
                    }
                },
            ),
        )
    }

    private suspend fun entries(patientId: PatientId): List<DoseLogEntry> {
        val items = medicationRepository.observe(patientId).first()
        val regimenIds = items.flatMap { it.regimens }.map { it.id }
        if (regimenIds.isEmpty()) return emptyList()
        val earliest = items.flatMap { it.regimens }.minOfOrNull { it.startDate } ?: today()
        return doseLogRepository.observeEntries(regimenIds, earliest, today()).first()
    }

    private fun entryJson(entry: DoseLogEntry): JsonElement = buildJsonObject {
        put("id", entry.id.value)
        put("regimen_id", entry.regimenId.value)
        put("medication_id", entry.medicationId.value)
        put("scheduled_local", entry.scheduledLocal.toString())
        put("outcome", entry.outcome.serverValue)
        put("actual_at", entry.actualAt.toString())
        put("confirmed_by", entry.confirmedBy)
        put("skip_reason", entry.skipReason?.serverValue)
        put("note", entry.note)
        put("actual_quantity", entry.actualQuantity)
        put("given_by_user_id", entry.givenByUserId)
        put("given_by_other", entry.givenByOther)
        put("given_by_name", entry.givenByName)
        put("corrects_entry_id", entry.correctsEntryId?.value)
    }
}

/** The patient's appointments: all rows in JSON, upcoming and last year in the PDF. */
class AppointmentsExportSource(
    private val appointmentRepository: AppointmentRepository,
    private val resources: Resources,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) : PatientExportSource {

    override val key: String = "appointments"

    override suspend fun json(patientId: PatientId): JsonElement {
        val appointments = appointmentRepository.observeForPatient(patientId).first()
        return buildJsonArray {
            appointments.forEach { appointment -> add(appointmentJson(appointment)) }
        }
    }

    override suspend fun pdfSections(patient: Patient): List<ExportSection> {
        val appointments = appointmentRepository.observeForPatient(patient.id).first()
        val current = now()
        val upcoming = appointments.filter { !it.isCancelled && it.startsLocal >= current }
        val yearAgo = current.minusMonths(12)
        val past = appointments
            .filter { it.startsLocal < current && it.startsLocal >= yearAgo }
            .sortedByDescending { it.startsLocal }
        return listOf(
            ExportSection(
                order = 20,
                title = resources.getString(R.string.export_appointments_upcoming),
                blocks = appointmentBlocks(upcoming),
            ),
            ExportSection(
                order = 21,
                title = resources.getString(R.string.export_appointments_past),
                blocks = appointmentBlocks(past),
            ),
        )
    }

    private fun appointmentBlocks(appointments: List<Appointment>): List<ExportBlock> =
        if (appointments.isEmpty()) {
            listOf(ExportBlock.Paragraph(resources.getString(R.string.export_none)))
        } else {
            appointments.map { appointment ->
                val cancelled = if (appointment.isCancelled) {
                    " (${resources.getString(R.string.export_cancelled)})"
                } else {
                    ""
                }
                ExportBlock.Paragraph(
                    "${appointment.startsLocal.toString().replace('T', ' ')} — " +
                        "${appointment.title.orEmpty()}$cancelled" +
                        appointment.place?.let { " — $it" }.orEmpty(),
                )
            }
        }

    private fun appointmentJson(appointment: Appointment): JsonElement = buildJsonObject {
        put("id", appointment.id.value)
        put("patient_id", appointment.patientId.value)
        put("kind", appointment.kind.serverValue)
        put("starts_local", appointment.startsLocal.toString())
        put("time_zone", appointment.timeZone.id)
        put("title", appointment.title)
        put("duration_minutes", appointment.durationMinutes)
        put("place", appointment.place)
        put("doctor", appointment.doctor)
        put("preparation", appointment.preparation)
        put("notes", appointment.notes)
        put("companion_user_id", appointment.companionUserId)
        put("follow_up_of", appointment.followUpOf?.value)
        put("cancelled_at", appointment.cancelledAt?.toString())
        put("created_at", appointment.createdAt?.toString())
        put("updated_at", appointment.updatedAt?.toString())
    }
}

/** The patient info: allergies (always first and prominent), conditions, SNS, doctor, contacts. */
class PatientInfoExportSource(
    private val patientInfoRepository: PatientInfoRepository,
    private val resources: Resources,
) : PatientExportSource {

    override val key: String = "patient_info"

    override suspend fun json(patientId: PatientId): JsonElement {
        val info = patientInfoRepository.observe(patientId).first()
            ?: return JsonNull
        return buildJsonObject {
            put("id", patientId.value)
            put("patient_id", patientId.value)
            put(
                "allergies",
                buildJsonArray { info.allergies.forEach { add(JsonPrimitive(it)) } },
            )
            put(
                "conditions",
                buildJsonArray { info.conditions.forEach { add(JsonPrimitive(it)) } },
            )
            put("sns_number", info.snsNumber)
            put("family_doctor", info.familyDoctor)
            put("health_centre", info.healthCentre)
            put("health_centre_phone", info.healthCentrePhone)
            put(
                "emergency_contacts",
                buildJsonArray {
                    info.emergencyContacts.forEach { contact ->
                        add(
                            buildJsonObject {
                                put("name", contact.name)
                                put("relationship", contact.relationship)
                                put("phone", contact.phone)
                            },
                        )
                    }
                },
            )
            put("notes", info.notes)
        }
    }

    override suspend fun pdfSections(patient: Patient): List<ExportSection> {
        val info = patientInfoRepository.observe(patient.id).first()
        val blocks = mutableListOf<ExportBlock>()
        blocks += ExportBlock.Row(
            resources.getString(R.string.export_name),
            patient.displayName,
        )
        info?.allergies?.takeIf { it.isNotEmpty() }?.let { allergies ->
            blocks += ExportBlock.Paragraph(
                resources.getString(R.string.export_allergies) + ": " + allergies.joinToString(", "),
                emphasis = true,
            )
        }
        info?.conditions?.takeIf { it.isNotEmpty() }?.let { conditions ->
            blocks += ExportBlock.Paragraph(
                resources.getString(R.string.export_conditions) + ": " + conditions.joinToString(", "),
            )
        }
        info?.snsNumber?.let {
            blocks += ExportBlock.Row(resources.getString(R.string.export_sns), it)
        }
        info?.familyDoctor?.let {
            blocks += ExportBlock.Row(resources.getString(R.string.export_family_doctor), it)
        }
        info?.healthCentre?.let {
            blocks += ExportBlock.Row(resources.getString(R.string.export_health_centre), it)
        }
        info?.healthCentrePhone?.let {
            blocks += ExportBlock.Row(resources.getString(R.string.export_health_centre_phone), it)
        }
        info?.emergencyContacts?.forEach { contact ->
            blocks += ExportBlock.Row(
                resources.getString(R.string.export_emergency_contact),
                listOfNotNull(contact.name, contact.relationship, contact.phone).joinToString(" — "),
            )
        }
        if (blocks.size == 1) {
            blocks += ExportBlock.Paragraph(resources.getString(R.string.export_none))
        }
        return listOf(
            ExportSection(
                order = 0,
                title = resources.getString(R.string.export_patient_info),
                blocks = blocks,
            ),
        )
    }
}
