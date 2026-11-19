package io.github.lgpy.familycare.feature.patientinfo.profile

import androidx.compose.runtime.Composable
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.PatientTab
import io.github.lgpy.familycare.core.ui.patient.PatientTabIcon
import io.github.lgpy.familycare.feature.patientinfo.PatientInfoRoutes

/**
 * The Info tab of the patient profile (D29, docs/domain/patient-info.md
 * "Screens"): SNS number, family doctor, health centre and its phone, the
 * emergency contacts and the notes.
 *
 * The module contributes it to the profile's extension point, so the app shell
 * opens this tab without importing the module's screens (D5). "Edit" opens the
 * module's own route through [onNavigate], and only for a role that may write.
 */
class InfoPatientTab : PatientTab {

    override val id: String = ID

    /** After the medications tab (10) and appointments (20), before History. */
    override val order: Int = 30

    override val titleRes: Int = R.string.patient_info_tab_title

    /** The patient's record gets the badge symbol; filled while selected. */
    override val icon: PatientTabIcon = PatientTabIcon.BADGE

    @Composable
    override fun Content(patientId: PatientId, onNavigate: (String) -> Unit) {
        PatientInfoSection(
            patientId = patientId.value,
            onEdit = { onNavigate(PatientInfoRoutes.edit(patientId.value)) },
        )
    }

    companion object {

        /** Stable id of the tab, unique across the contributions. */
        const val ID: String = "info"
    }
}
