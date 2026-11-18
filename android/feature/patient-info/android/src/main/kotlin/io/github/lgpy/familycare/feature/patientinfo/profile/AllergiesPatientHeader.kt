package io.github.lgpy.familycare.feature.patientinfo.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.PatientHeader
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The allergies banner and conditions line of the patient profile (D29,
 * docs/domain/patient-info.md "Screens").
 *
 * Allergies are shown **only when non-empty**, always in the error container
 * colour: the banner is a warning, and the profile never claims "no known
 * allergies" — absence of a record is not a clinical statement. The conditions
 * line sits directly under it, and when both lists are empty the header renders
 * nothing at all.
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
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (uiState.showsAllergies && info != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(
                            R.string.patient_info_allergies_banner,
                            info.allergies.joinToString(separator),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (uiState.showsConditions && info != null) {
                Text(
                    text = stringResource(
                        R.string.patient_info_conditions_line,
                        info.conditions.joinToString(separator),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    companion object {

        /** First header: directly under the patient's name. */
        const val ORDER: Int = 0
    }
}
