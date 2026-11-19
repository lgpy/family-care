package io.github.lgpy.familycare.feature.patientinfo.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.AllergyBanner
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.patient.PatientHeader
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The allergies banner and conditions chips of the patient profile (D29,
 * docs/domain/patient-info.md "Screens"; docs/ux/mockups/screens-v2.html
 * profile).
 *
 * Allergies are the one critical message of the profile: they use the design
 * system's [AllergyBanner] (fixed critical colours, never truncated) and sit
 * directly under the patient's name, above the tabs, so they are visible
 * without opening a tab. They are still shown **only when non-empty**: the
 * profile never claims "no known allergies" — absence of a record is not a
 * clinical statement.
 *
 * Conditions are chips under the banner, each with the generic heart-rhythm
 * icon (screens v2: no per-condition icons). Both rows wrap and grow instead of
 * truncating; when both lists are empty the header renders nothing.
 *
 * [order] 0 puts it first, directly under the patient's name.
 */
class AllergiesPatientHeader : PatientHeader {

    override val order: Int = ORDER

    @OptIn(ExperimentalLayoutApi::class)
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    info.conditions.forEach { condition ->
                        ConditionChip(text = condition)
                    }
                }
            }
        }
    }

    companion object {

        /** First header: directly under the patient's name. */
        const val ORDER: Int = 0
    }
}

/**
 * One condition: the outlined chip of screens v2, with the generic heart-rhythm
 * icon. It wraps with its text instead of truncating.
 */
@Composable
private fun ConditionChip(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = FcIcons.EcgHeart,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}
