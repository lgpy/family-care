package io.github.lgpy.familycare.feature.patientinfo.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.AllergyBanner
import io.github.lgpy.familycare.core.ui.patient.PatientHeader
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The allergies banner and conditions line of the patient profile (D29,
 * docs/domain/patient-info.md "Screens"; docs/ux/mockups/scaling.html profile).
 *
 * Allergies are the one critical message of the profile: they use the design
 * system's [AllergyBanner] (fixed critical colours, never truncated) and sit
 * directly under the patient's name, above the tabs, so they are visible
 * without opening a tab. They are still shown **only when non-empty**: the
 * profile never claims "no known allergies" — absence of a record is not a
 * clinical statement. The conditions line is small `onSurfaceVariant` text; when
 * both lists are empty the header renders nothing.
 *
 * [order] 0 puts it first, directly under the patient's name.
 */
class AllergiesPatientHeader : PatientHeader {

    override val order: Int = ORDER

    @Composable
    override fun Content(patientId: PatientId, onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<PatientInfoViewModel>(
            parameters = { parametersOf(patientId.value) },
        )
        val uiState by viewModel.uiState.collectAsState()

        if (!uiState.isHeaderVisible) return

        val info = uiState.info
        val separator = stringResource(R.string.patient_info_list_separator)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (uiState.showsAllergies && info != null) {
                AllergyBanner(
                    text = stringResource(
                        R.string.patient_info_allergies_banner,
                        info.allergies.joinToString(separator),
                    ),
                )
            }
            if (uiState.showsConditions && info != null) {
                Text(
                    text = stringResource(
                        R.string.patient_info_conditions_line,
                        info.conditions.joinToString(separator),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    companion object {

        /** First header: directly under the patient's name. */
        const val ORDER: Int = 0
    }
}
