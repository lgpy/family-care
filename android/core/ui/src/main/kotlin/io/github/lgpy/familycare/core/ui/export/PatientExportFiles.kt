package io.github.lgpy.familycare.core.ui.export

import java.io.File

/**
 * Where an export is written and what it is called (D30).
 *
 * Files live in `cache/exports/`, are shared through the FileProvider declared
 * in the app's manifest, and are deleted the next time an export starts or the
 * app starts, so a copy never lingers on the phone.
 */
object PatientExportFiles {

    /** `cacheDir/exports`, the only directory the FileProvider exposes. */
    const val DIRECTORY = "exports"

    /** Deletes every file in [directory] (ignoring subdirectories). */
    fun clear(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    /**
     * `family-care-<patient>-<yyyyMMdd>.<extension>`, with the name sanitized
     * so a patient called "Avó / Maria" cannot escape the exports directory or
     * break a share target.
     */
    fun fileName(patientName: String, date: String, extension: String): String =
        "family-care-${sanitize(patientName)}-$date.$extension"

    /**
     * Keeps letters, digits and `-`/`_`; every other run becomes one `-`.
     * Accented letters are kept (they are safe in a file name and mean
     * something to the user), so only path separators and the like change.
     */
    fun sanitize(name: String): String {
        val cleaned = name.trim()
            .map { character ->
                when {
                    character.isLetterOrDigit() -> character
                    character == '-' || character == '_' -> character
                    else -> '-'
                }
            }
            .joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trim('-', ' ')
        return cleaned.ifEmpty { "patient" }
    }
}
