package io.github.lgpy.familycare.core.ui.patient

import io.github.lgpy.familycare.core.domain.circle.PatientId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/*
 * The patient alerts extension point (D5, D29).
 *
 * Allergies belong to the patient-info module, but the Give screen (medications)
 * must show the same red banner, and a feature module may never import another
 * (D5). The shared contract therefore lives in `core/ui` next to the other
 * profile extension points: the module that owns the data implements it, the app
 * shell registers that implementation in Koin, and a screen that needs it injects
 * the interface with a no-op fallback.
 */

/**
 * A patient's safety alerts, read by a screen that is not patient-info.
 *
 * The only alert today is the allergy list; it is deliberately a list of
 * already-localized strings, exactly as the profile banner prints them.
 * Implementations are registered by the app shell (D5); a screen must work when
 * none is registered (unit tests, a stripped build) and then sees no alerts.
 */
interface PatientAlerts {

    /**
     * The patient's allergies, empty when none are recorded (absence of a
     * record is not a clinical statement, so callers show nothing at all).
     */
    fun allergies(patientId: PatientId): Flow<List<String>>
}

/**
 * The safe default: no alerts. A screen uses this when the app shell registered
 * no [PatientAlerts] implementation, so missing patient-info wiring never
 * crashes the Give screen.
 */
object NoPatientAlerts : PatientAlerts {

    override fun allergies(patientId: PatientId): Flow<List<String>> = flowOf(emptyList())
}
