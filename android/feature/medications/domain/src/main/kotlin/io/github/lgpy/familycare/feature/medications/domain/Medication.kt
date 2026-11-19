package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.domain.medication.PillLookRules
import java.time.Instant
import java.time.LocalDate

/**
 * How a medication is taken (docs/domain/medications.md "Medication").
 *
 * The database stores the lowercase [serverValue]; the screen shows a
 * localized label for the enum constant. Unknown server values are not
 * silently mapped: [fromServer] returns null so a row from a newer server can
 * be reported instead of being rewritten as [OTHER].
 */
enum class MedicationForm(val serverValue: String) {
    TABLET("tablet"),
    CAPSULE("capsule"),
    DROPS("drops"),
    LIQUID("liquid"),
    INJECTION("injection"),
    INHALER("inhaler"),
    PATCH("patch"),
    CREAM("cream"),
    OTHER("other"),
    ;

    companion object {
        /** The form with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): MedicationForm? =
            entries.firstOrNull { it.serverValue == value }
    }
}

/**
 * One medication of one patient.
 *
 * Instances come from the synced local database, so they are only as fresh as
 * the last download. Persist a new or edited one through the feature's
 * repository; [normalized] and [validate] are the rules it must pass first.
 *
 * @param patientId The patient the medication belongs to. A medication never
 *   moves to another patient: stop it and add a new one.
 * @param strength Free text as printed on the box (`500 mg`, `10 mg/5 ml`).
 * @param stockUnit What stock is counted in (`tablet`, `ml`, `pen`). Required:
 *   the M5 ledger subtracts doses in this unit.
 * @param prescriptionValidUntil Null when the medication needs no prescription
 *   or the date is not known. A date in the past means "expired" and is a
 *   shopping-list state, not a validation error.
 * @param pillLook How the pill is drawn (shape, colour, score line, size; D33).
 *   Null when nobody has set it yet: the UI then draws a neutral outline.
 * @param lowStockThresholdDays Days of remaining stock at which `StockLow` is
 *   raised. 0 disables the alert for this medication.
 * @param reminderPolicy Overrides the patient's policy when set; null inherits
 *   it (docs/domain/care-circle.md).
 * @param primaryCaregiverId Overrides the patient's primary caregiver when set;
 *   null inherits.
 * @param stoppedAt Set when the medication is stopped; the regimen is ended
 *   separately ([RegimenRules.stop]). Null while active.
 */
data class Medication(
    val id: MedicationId,
    val patientId: PatientId,
    val name: String,
    val activeIngredient: String? = null,
    val strength: String? = null,
    val form: MedicationForm,
    val stockUnit: String,
    val instructions: String? = null,
    val requiresPrescription: Boolean = false,
    val prescriptionValidUntil: LocalDate? = null,
    val pillLook: PillLook? = null,
    val lowStockThresholdDays: Int = MedicationRules.DEFAULT_LOW_STOCK_THRESHOLD_DAYS,
    val reminderPolicy: ReminderPolicy? = null,
    val primaryCaregiverId: String? = null,
    val stoppedAt: Instant? = null,
) {

    /** Whether the medication is stopped (hidden from Today, no new doses). */
    val isStopped: Boolean get() = stoppedAt != null

    /**
     * The values as they should be stored: the name and stock unit trimmed, a
     * blank optional text turned into null. Nothing else changes.
     */
    fun normalized(): Medication = copy(
        name = MedicationRules.normalizeText(name),
        activeIngredient = MedicationRules.normalizeOptional(activeIngredient),
        strength = MedicationRules.normalizeOptional(strength),
        stockUnit = MedicationRules.normalizeText(stockUnit),
        instructions = MedicationRules.normalizeOptional(instructions),
        pillLook = pillLook?.let(PillLookRules::normalize),
        primaryCaregiverId = MedicationRules.normalizeOptional(primaryCaregiverId),
    )

    /** Intrinsic validity, mirroring the `meds.medications` check constraints. */
    fun validate(): MedicationValidation = MedicationRules.validate(this)
}

/**
 * The medication rules from docs/domain/medications.md, in one place.
 *
 * The lengths mirror the check constraints of the `meds` schema so a local
 * write is rejected before it reaches the upload queue, where a constraint
 * failure could only be reported as a permanent error. The server (TypeScript
 * and Postgres) applies the same numbers; changing one means changing both
 * sides and the shared vectors.
 */
object MedicationRules {

    /** Mirrors `meds.medications_name_length`. */
    const val MAX_NAME_LENGTH: Int = 200

    /** Mirrors `meds.medications_active_ingredient_length`. */
    const val MAX_ACTIVE_INGREDIENT_LENGTH: Int = 200

    /** Mirrors `meds.medications_strength_length`. */
    const val MAX_STRENGTH_LENGTH: Int = 100

    /** Mirrors `meds.medications_stock_unit_length`. */
    const val MAX_STOCK_UNIT_LENGTH: Int = 50

    /** Mirrors `meds.medications_instructions_length`. */
    const val MAX_INSTRUCTIONS_LENGTH: Int = 1000

    /** Mirrors `meds.medications_low_stock_threshold_range`. */
    const val MIN_LOW_STOCK_THRESHOLD_DAYS: Int = 0
    const val MAX_LOW_STOCK_THRESHOLD_DAYS: Int = 365

    /** docs/domain/medications.md: 7 days. */
    const val DEFAULT_LOW_STOCK_THRESHOLD_DAYS: Int = 7

    /** Trims the text fields the server stores trimmed. */
    fun normalizeText(value: String): String = value.trim()

    /** A blank optional text is stored as null; nothing else is changed. */
    fun normalizeOptional(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    /** Whether [name] is acceptable (1..200 characters after trimming). */
    fun isValidName(name: String): Boolean =
        normalizeText(name).length in 1..MAX_NAME_LENGTH

    /** Length of an optional text after trimming; 0 when it is blank. */
    private fun lengthOrZero(value: String?): Int =
        normalizeOptional(value)?.length ?: 0

    /** The intrinsic rules: lengths, ranges and the offered reminder policy. */
    fun validate(medication: Medication): MedicationValidation {
        val errors = mutableSetOf<MedicationField>()

        if (!isValidName(medication.name)) {
            errors += MedicationField.MEDICATION_NAME
        }
        if (lengthOrZero(medication.activeIngredient) > MAX_ACTIVE_INGREDIENT_LENGTH) {
            errors += MedicationField.MEDICATION_ACTIVE_INGREDIENT
        }
        if (lengthOrZero(medication.strength) > MAX_STRENGTH_LENGTH) {
            errors += MedicationField.MEDICATION_STRENGTH
        }
        if (normalizeText(medication.stockUnit).length !in 1..MAX_STOCK_UNIT_LENGTH) {
            errors += MedicationField.MEDICATION_STOCK_UNIT
        }
        if (lengthOrZero(medication.instructions) > MAX_INSTRUCTIONS_LENGTH) {
            errors += MedicationField.MEDICATION_INSTRUCTIONS
        }
        if (medication.pillLook != null && PillLookRules.validate(medication.pillLook).isNotEmpty()) {
            errors += MedicationField.MEDICATION_PILL_LOOK
        }
        if (medication.lowStockThresholdDays !in
            MIN_LOW_STOCK_THRESHOLD_DAYS..MAX_LOW_STOCK_THRESHOLD_DAYS
        ) {
            errors += MedicationField.MEDICATION_LOW_STOCK_THRESHOLD
        }
        if (medication.reminderPolicy?.isOffered == false) {
            // ROTA is modelled in the core but not built; the app must not save it.
            errors += MedicationField.MEDICATION_REMINDER_POLICY
        }
        if (medication.primaryCaregiverId != null &&
            normalizeOptional(medication.primaryCaregiverId) == null
        ) {
            errors += MedicationField.MEDICATION_PRIMARY_CAREGIVER
        }

        return MedicationValidation(errors)
    }
}
