package io.github.lgpy.familycare.core.ui.export

import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.PatientExportFormat
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** What an export produced, or why it did not. */
sealed interface PatientExportOutcome {

    /** The file on disk; the screen turns it into a FileProvider `Uri` and shares it. */
    data class Success(val file: File, val format: PatientExportFormat) : PatientExportOutcome

    /**
     * Nothing was written. [Offline] and [NotAllowed] are the two the screen
     * explains in its own words; anything else is the generic failure.
     */
    data class Failure(val error: CoreError) : PatientExportOutcome
}

/**
 * Generates one patient export (D30, docs/domain/patient-lifecycle.md
 * "Export").
 *
 * The order is the contract: the patient is read locally, then
 * [PatientRepository.logExport] records the audit row **before** any file
 * exists. A refused or offline log therefore produces no file, and the screen
 * can say why. Only then are the sources asked for their data and the bytes
 * written to `cache/exports/`.
 *
 * Everything is local except the log call, so the export reflects the phone's
 * synced data.
 */
class PatientExporter(
    private val patientRepository: PatientRepository,
    private val sources: PatientExportSources,
    private val directory: File,
    private val now: () -> Instant = { Instant.now() },
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Deletes leftovers: called at export start and at app start. */
    fun clear() = PatientExportFiles.clear(directory)

    /** Generates the export for [patientId] in [format]. */
    suspend fun export(
        patientId: PatientId,
        format: PatientExportFormat,
    ): PatientExportOutcome {
        val patient = patientRepository.observe(patientId).first()
            ?: return PatientExportOutcome.Failure(CoreError.NotFound)

        when (val logged = patientRepository.logExport(patientId, format)) {
            is CoreResult.Failure -> return PatientExportOutcome.Failure(logged.error)
            is CoreResult.Success -> Unit
        }

        return try {
            clear()
            directory.mkdirs()
            val file = File(
                directory,
                PatientExportFiles.fileName(
                    patientName = patient.displayName,
                    date = today().format(dateFormat),
                    extension = format.fileExtension,
                ),
            )
            when (format) {
                PatientExportFormat.JSON -> {
                    val document = sources.json(patient, now())
                    file.writeText(Json.encodeToString(document))
                }

                PatientExportFormat.PDF -> {
                    file.outputStream().use { output ->
                        PatientExportPdf.write(sources.pdfSections(patient), output)
                    }
                }
            }
            PatientExportOutcome.Success(file, format)
        } catch (_: Exception) {
            PatientExportOutcome.Failure(CoreError.Unknown)
        }
    }
}
