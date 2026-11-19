package io.github.lgpy.familycare.feature.medications.profile

import androidx.compose.runtime.Composable
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.PatientTab
import io.github.lgpy.familycare.core.ui.patient.PatientTabIcon
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.list.PatientMedicationsSection

/**
 * The Medications tab of the patient profile (D29, docs/ux/screens.md §3).
 *
 * The module contributes it to the profile's extension point, so the app shell
 * opens this tab without importing the medications screens (D5). The content is
 * the module's existing per-patient list, embedded below the profile's tabs;
 * tapping a medication opens the module's detail route through [onNavigate].
 */
class MedicationsPatientTab : PatientTab {

    override val id: String = ID

    /** After any header-like tab, before appointments' tab. */
    override val order: Int = 10

    override val titleRes: Int = R.string.meds_tab_title

    /** Medicines get the medicines symbol; filled while selected. */
    override val icon: PatientTabIcon = PatientTabIcon.MEDICATION

    @Composable
    override fun Content(patientId: PatientId, onNavigate: (String) -> Unit) {
        PatientMedicationsSection(
            patientId = patientId.value,
            onAdd = { onNavigate(MedicationsRoutes.newMedication(patientId.value)) },
            onOpenMedication = { medicationId ->
                onNavigate(MedicationsRoutes.medicationDetail(patientId.value, medicationId))
            },
        )
    }

    companion object {

        /** Stable id of the tab, unique across the contributions. */
        const val ID: String = "medications"
    }
}
