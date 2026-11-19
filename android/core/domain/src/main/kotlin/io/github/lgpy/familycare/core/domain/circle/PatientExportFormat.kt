package io.github.lgpy.familycare.core.domain.circle

/**
 * The two formats a patient export can be written in (D30,
 * docs/domain/patient-lifecycle.md "Export").
 *
 * [serverValue] is what `core.log_patient_export(p_format)` expects; the audit
 * row stores only this word, never any health data.
 */
enum class PatientExportFormat(val serverValue: String) {

    /** The machine-readable export: `family-care-patient-export` JSON. */
    JSON("json"),

    /** The readable summary for a doctor, generated with `PdfDocument`. */
    PDF("pdf"),
    ;

    /** `application/json` or `application/pdf`, for the share sheet. */
    val mimeType: String
        get() = when (this) {
            JSON -> "application/json"
            PDF -> "application/pdf"
        }

    /** `json` or `pdf`, the file extension. */
    val fileExtension: String get() = serverValue
}
