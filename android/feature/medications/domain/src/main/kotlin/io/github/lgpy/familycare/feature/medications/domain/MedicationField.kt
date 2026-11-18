package io.github.lgpy.familycare.feature.medications.domain

/**
 * The inputs a medications-domain rule can point at, so a form can highlight
 * the offending field without a message.
 *
 * Deliberately local to the feature rather than added to the core's
 * [io.github.lgpy.familycare.core.domain.circle.CoreField]: the core does not
 * know about medications, and a future medications screen maps these values to
 * its own labels (D5, D8).
 */
enum class MedicationField {
    // --- Medication --------------------------------------------------------
    MEDICATION_NAME,
    MEDICATION_ACTIVE_INGREDIENT,
    MEDICATION_STRENGTH,
    MEDICATION_STOCK_UNIT,
    MEDICATION_INSTRUCTIONS,
    /**
     * The free-text prescription validity date a form asks for. The domain keeps
     * a past date valid on purpose (it is a shopping-list state), so this marks
     * only an unparseable one; the column itself has no server-side field name.
     */
    MEDICATION_PRESCRIPTION_VALID_UNTIL,
    MEDICATION_LOW_STOCK_THRESHOLD,
    MEDICATION_REMINDER_POLICY,
    MEDICATION_PRIMARY_CAREGIVER,

    // --- Regimen -----------------------------------------------------------
    REGIMEN_SCHEDULE,
    REGIMEN_WEEKDAYS,
    REGIMEN_INTERVAL_DAYS,
    REGIMEN_START_DATE,
    REGIMEN_END_DATE,
    REGIMEN_TIMES,
    REGIMEN_OVERLAP,

    // --- Dose log ----------------------------------------------------------
    DOSE_REGIMEN,
    DOSE_MEDICATION,
    DOSE_SCHEDULED_TIME,
    DOSE_OUTCOME,
    DOSE_SKIP_REASON,
    DOSE_QUANTITY,
    DOSE_CONFIRMED_BY,
    DOSE_GIVEN_BY,
    DOSE_NOTE,
    DOSE_CORRECTION,

    // --- Stock ledger (D28) ------------------------------------------------
    /** The quantity of a stock movement: ≥ 0 for a count, > 0 for refill/discard. */
    STOCK_QUANTITY,

    /** The reason of a discard: required and up to 500 characters. */
    STOCK_REASON,
}

/**
 * What a medications-domain rule rejected.
 *
 * One type serves all three rows (medication, regimen, dose-log entry) because
 * [MedicationField] values are disjoint: a validation never mixes fields of
 * different rows. Mirrors the core's
 * [io.github.lgpy.familycare.core.domain.circle.PatientValidation] so callers
 * handle both the same way.
 */
data class MedicationValidation(val errors: Set<MedicationField> = emptySet()) {

    /** Whether the row can be stored as it is. */
    val isValid: Boolean get() = errors.isEmpty()
}
