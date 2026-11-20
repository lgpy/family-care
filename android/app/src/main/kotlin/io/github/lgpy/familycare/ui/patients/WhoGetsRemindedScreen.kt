package io.github.lgpy.familycare.ui.patients

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import org.koin.compose.viewmodel.koinViewModel

/**
 * More → "Who gets reminded" (screens v2, docs/ux/screens.md §7): the circle's
 * patients, each with their current reminder policy in one line.
 *
 * It is a plain list, not the reliability checklist: the checklist answers
 * "can this phone remind me?", while this answers "who is reminded for whom?".
 * Tapping a person opens their existing edit form, which owns the policy and
 * primary caregiver controls.
 */
@Composable
fun WhoGetsRemindedScreen(
    onBack: () -> Unit,
    onOpenPatient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<WhoGetsRemindedViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = FcIcons.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                ScreenHeader(
                    title = stringResource(R.string.more_who_reminded),
                    subtitle = stringResource(R.string.who_reminded_intro),
                )
            }
        }

        when {
            !uiState.loaded -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))

            uiState.rows.isEmpty() -> Text(
                text = stringResource(R.string.who_reminded_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.rows, key = { it.patient.id.value }) { row ->
                    ReminderPatientRow(
                        row = row,
                        onClick = { onOpenPatient(row.patient.id.value) },
                    )
                }
            }
        }
    }
}

/**
 * One patient: the avatar, the name and the reminder summary, with the same
 * warning the Patients list shows when a removed member left nobody to remind.
 */
@Composable
private fun ReminderPatientRow(
    row: WhoGetsRemindedRow,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PatientAvatar(
            patientId = row.patient.id,
            name = row.patient.displayName,
            size = 44.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = row.patient.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            reminderSummary(
                policy = row.patient.reminderPolicy,
                primaryCaregiverName = row.primaryCaregiverName,
                primaryCaregiverIsMe = row.primaryCaregiverIsMe,
                escalationDelayMinutes = row.escalationDelayMinutes,
            )?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (row.patient.needsPrimaryCaregiver) {
                Text(
                    text = stringResource(R.string.patient_needs_primary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Icon(
            imageVector = FcIcons.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
    }
}
