package io.github.lgpy.familycare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCards
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Today: the start destination, sorted by urgency rather than by module
 * (docs/ux/screens.md §1).
 *
 * The greeting, the circle and the role come from the core; every card below
 * comes from a feature module through the dashboard extension point
 * ([DashboardCards]). Today imports no module: it draws what was registered and
 * passes [onOpenRoute] so a card can open its own screens.
 */
@Composable
fun TodayScreen(
    modifier: Modifier = Modifier,
    onOpenRoute: (String) -> Unit = {},
) {
    val viewModel = koinViewModel<TodayViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val dashboard = koinInject<DashboardCards>()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        dashboard.ordered.forEach { card ->
            card.Content(onNavigate = onOpenRoute)
        }
    }
}

private fun MemberRole.messageRes(): Int = when (this) {
    MemberRole.OWNER -> R.string.today_role_owner
    MemberRole.CAREGIVER -> R.string.today_role_caregiver
    MemberRole.VIEWER -> R.string.today_role_viewer
}
