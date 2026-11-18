package io.github.lgpy.familycare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.ui.R
import org.koin.compose.viewmodel.koinViewModel

/**
 * Today: the start destination. It names the signed-in member and their circle,
 * so it is already correct for the real family; the dashboard cards arrive with
 * the medication module (M3).
 */
@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<TodayViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = uiState.displayName?.let { stringResource(R.string.today_greeting, it) }
                ?: stringResource(R.string.today_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        uiState.circleName?.let { name ->
            Text(
                text = stringResource(R.string.today_circle, name),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        uiState.role?.let { role ->
            Text(
                text = stringResource(role.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (uiState.patientCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.today_patients_count,
                    uiState.patientCount,
                    uiState.patientCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.today_placeholder),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun MemberRole.messageRes(): Int = when (this) {
    MemberRole.OWNER -> R.string.today_role_owner
    MemberRole.CAREGIVER -> R.string.today_role_caregiver
    MemberRole.VIEWER -> R.string.today_role_viewer
}
