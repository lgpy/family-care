package io.github.lgpy.familycare.feature.medications.domain

/**
 * Identity of one `meds.regimens` row.
 *
 * A regimen is never edited in place (D9): a dose change ends it and inserts a
 * new regimen with a new id, so history stays addressable by id. Generated on
 * the phone, so it works offline.
 */
@JvmInline
value class RegimenId(val value: String) {
    init {
        require(value.isNotBlank()) { "A regimen id must not be blank" }
    }
}

/**
 * Identity of one append-only `meds.dose_log` entry (D6).
 *
 * Corrections point at another entry through
 * [DoseLogEntry.correctsEntryId]; they never replace it.
 */
@JvmInline
value class DoseLogEntryId(val value: String) {
    init {
        require(value.isNotBlank()) { "A dose log entry id must not be blank" }
    }
}
