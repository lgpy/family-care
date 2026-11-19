package io.github.lgpy.familycare.feature.medications.ui

import java.time.LocalDate

/**
 * What the detail screen's prescription card says about one medication.
 *
 * Derived only from [io.github.lgpy.familycare.feature.medications.domain.Medication.requiresPrescription]
 * and its validity date, never from the shopping list: the list only holds a
 * low medication, so using its state would make the card contradict itself when
 * the stock is still fine (the Metformin bug: "No prescription needed" over
 * "Needs a new prescription first").
 */
enum class PrescriptionState {
    /** A prescription is required and it is missing or expired: request a new one. */
    NEEDED,

    /** A prescription is required and its last valid day is today or later. */
    VALID,

    /** The medication can be bought without a prescription. */
    NOT_REQUIRED,
}

/**
 * The card's state, exactly the rule the domain uses for the shopping list
 * ([io.github.lgpy.familycare.feature.medications.domain.StockSummary.Companion.shoppingStatus]):
 * valid through the recorded day itself.
 */
fun prescriptionState(
    requiresPrescription: Boolean,
    prescriptionValidUntil: LocalDate?,
    today: LocalDate,
): PrescriptionState = when {
    !requiresPrescription -> PrescriptionState.NOT_REQUIRED
    prescriptionValidUntil == null || prescriptionValidUntil.isBefore(today) ->
        PrescriptionState.NEEDED

    else -> PrescriptionState.VALID
}
