package io.github.lgpy.familycare.feature.patientinfo.profile

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryIcon
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryItem
import io.github.lgpy.familycare.core.ui.patient.PatientSummarySource
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryText
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryTone
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The patient-info module's tag on the Patients list: **Allergies**, in the
 * critical colours, when the patient has any recorded.
 *
 * Absence of a record is not a clinical statement, so a patient with no
 * allergies (or no info row at all) contributes nothing; the tag is never
 * "no known allergies". The text is localized here through [PatientSummaryText]
 * (D5).
 */
class PatientInfoPatientSummarySource(
    private val repository: PatientInfoRepository,
    private val text: PatientSummaryText,
) : PatientSummarySource {

    /** Last: the safety tag sits beside the row's facts. */
    override val order: Int = ORDER

    override fun summary(patientId: PatientId): Flow<List<PatientSummaryItem>> =
        repository.observe(patientId).map { info ->
            if (info == null || info.allergies.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    PatientSummaryItem(
                        icon = PatientSummaryIcon.WARNING,
                        text = text.get(R.string.patients_tag_allergies),
                        tone = PatientSummaryTone.CRITICAL,
                    ),
                )
            }
        }

    companion object {

        /** After medications (10) and appointments (20). */
        const val ORDER: Int = 30
    }
}
