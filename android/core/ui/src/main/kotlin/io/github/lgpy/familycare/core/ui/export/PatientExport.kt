package io.github.lgpy.familycare.core.ui.export

import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The export extension point (D30, docs/domain/patient-lifecycle.md "Export").
 *
 * The app owns the export screen and the file, but every module's data belongs
 * in it (D5): a feature contributes a [PatientExportSource] that reads its own
 * local tables through its own repositories, exactly as it contributes a
 * DashboardCard to Today. The app registers the sources in Koin and this
 * package never imports a feature module.
 */

/**
 * One module's data for a patient export.
 *
 * [key] is the JSON object's key (`medications`, `dose_log`, `appointments`,
 * `patient_info`); it must be unique across the registered sources so no
 * module silently overwrites another's section.
 */
interface PatientExportSource {

    /** The top-level JSON key this source fills. */
    val key: String

    /**
     * This module's rows for [patientId], with the database column names
     * (snake_case), as the export's JSON contract fixes them.
     */
    suspend fun json(patientId: PatientId): JsonElement

    /**
     * This module's readable sections for the PDF, already localized with the
     * app's language through the source's own string lookup.
     */
    suspend fun pdfSections(patient: Patient): List<ExportSection>
}

/** One titled block of the readable PDF export. */
data class ExportSection(
    /** Position in the document, lower first: patient info, medications, appointments, dose log. */
    val order: Int,
    /** The heading, already localized. */
    val title: String,
    val blocks: List<ExportBlock>,
)

/** One piece of a [ExportSection]: a paragraph or a label/value line. */
sealed interface ExportBlock {

    /** A free line of text. [emphasis] is used for allergies and other warnings. */
    data class Paragraph(val text: String, val emphasis: Boolean = false) : ExportBlock

    /** A label/value line, e.g. "Family doctor — Dr. Silva". */
    data class Row(val label: String, val value: String) : ExportBlock
}

/**
 * Every contributed source, in registration order.
 *
 * [json] assembles the document the domain fixes:
 * `{"format","version","exported_at","patient":{…},<source keys>}` with the
 * patient's row built by [PatientExportJson]. [pdfSections] concatenates the
 * modules' sections and sorts them by [ExportSection.order], so the document
 * reads patient info, medications, appointments, dose log.
 */
class PatientExportSources(private val sources: List<PatientExportSource>) {

    init {
        val duplicates = sources.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Patient export keys must be unique; registered twice: ${duplicates.joinToString()}"
        }
    }

    /** The whole export document for [patient], as of [exportedAt]. */
    suspend fun json(patient: Patient, exportedAt: Instant): JsonObject =
        buildJsonObject {
            put(FORMAT_KEY, FORMAT_NAME)
            put(VERSION_KEY, VERSION)
            put(EXPORTED_AT_KEY, exportedAt.toString())
            put(PATIENT_KEY, PatientExportJson.patient(patient))
            sources.forEach { source ->
                put(source.key, source.json(patient.id))
            }
        }

    /** Every module's sections, in document order. */
    suspend fun pdfSections(patient: Patient): List<ExportSection> =
        sources.flatMap { it.pdfSections(patient) }.sortedBy { it.order }

    private companion object {
        const val FORMAT_KEY = "format"
        const val FORMAT_NAME = "family-care-patient-export"
        const val VERSION_KEY = "version"
        const val VERSION = 1
        const val EXPORTED_AT_KEY = "exported_at"
        const val PATIENT_KEY = "patient"
    }
}

/**
 * The core's own part of the document: the `core.patients` row with database
 * column names. A feature module never reads this table directly for the
 * export; the app passes the patient it already observes.
 */
object PatientExportJson {

    /** The patient's row as exported (snake_case, the database's columns). */
    fun patient(patient: Patient): JsonObject = buildJsonObject {
        put("id", patient.id.value)
        put("circle_id", patient.circleId.value)
        put("display_name", patient.displayName)
        put("linked_user_id", patient.linkedUserId)
        put("reminder_policy", patient.reminderPolicy.serverValue)
        put("primary_caregiver_id", patient.primaryCaregiverId)
        put("escalation_delay_minutes", patient.escalationDelayMinutes)
        put("missed_window_minutes", patient.missedWindowMinutes)
        put("agreement_note", patient.agreementNote)
        put("archived_at", patient.archivedAt?.toString())
    }

    /** A nullable text column: JSON null when absent, the value otherwise. */
    internal fun nullable(value: String?): JsonElement =
        value?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull
}
