package io.github.lgpy.familycare.ui.patients

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryIcon
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryItem
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryTone
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import java.time.LocalDate
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Patients tab (screens v2): a list of the people in the circle, plus an
 * "Add patient" button for owners and caregivers (the same rule the server
 * enforces). A row opens the person's profile (D29).
 *
 * Each row is the person's identity (44 dp avatar, name and age), the facts the
 * feature modules contribute (the medicines count, the next appointment) and
 * their tags — allergies in critical colours, low stock in attention colours.
 * The app names no feature module (D5): it draws whatever
 * [io.github.lgpy.familycare.core.ui.patient.PatientSummarySource] contributed.
 */
@Composable
fun PatientsScreen(
    onAddPatient: () -> Unit,
    onOpenPatient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientsViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    var archivedExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        // A tab inside the app's Scaffold: the outer one already handles the
        // system bars, so this one must not add the status bar again.
        contentWindowInsets = WindowInsets(0),
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (uiState.canWrite) {
                ExtendedFloatingActionButton(
                    onClick = onAddPatient,
                    icon = {
                        Icon(
                            imageVector = FcIcons.PersonAdd,
                            contentDescription = null,
                        )
                    },
                    text = { Text(text = stringResource(R.string.patients_add)) },
                )
            }
        },
    ) { padding ->
        if (uiState.patients.isEmpty() && uiState.archivedPatients.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ScreenHeader(title = stringResource(R.string.patients_title))
                Text(
                    text = stringResource(R.string.patients_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    ScreenHeader(
                        title = stringResource(R.string.patients_title),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(uiState.patients, key = { it.patient.id.value }) { row ->
                    PatientListRow(
                        row = row,
                        onClick = { onOpenPatient(row.patient.id.value) },
                    )
                }
                if (uiState.archivedPatients.isNotEmpty()) {
                    item(key = "archived-header") {
                        // D30: archived patients stay reachable, but out of the way.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = archivedExpanded,
                                    role = Role.Button,
                                    onClick = { archivedExpanded = !archivedExpanded },
                                )
                                .padding(top = 16.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.patients_archived_section,
                                    uiState.archivedPatients.size,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    if (archivedExpanded) {
                        items(uiState.archivedPatients, key = { it.patient.id.value }) { row ->
                            PatientListRow(
                                row = row,
                                onClick = { onOpenPatient(row.patient.id.value) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One person: the avatar, the name with the age, the modules' meta line and
 * their tags, and a chevron into the profile.
 *
 * Nothing truncates: the name, the facts and the tags wrap and the card grows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatientListRow(
    row: PatientRowUi,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val neutral = row.summary.filter { it.tone == PatientSummaryTone.NEUTRAL }
    val tags = row.summary.filter { it.tone != PatientSummaryTone.NEUTRAL }
    val age = patientAge(row.patient.birthYear)

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
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = row.patient.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                age?.let { years ->
                    Text(
                        text = years.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (neutral.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    neutral.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = item.icon.image(),
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { tag -> SummaryTag(tag) }
                }
            }
            if (row.patient.needsPrimaryCaregiver) {
                // D24: a removed member left this patient with nobody to remind.
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

/**
 * One tag: red for allergies ([PatientSummaryTone.CRITICAL]), amber for
 * something to do ([PatientSummaryTone.ATTENTION]); the fixed semantic colours
 * never follow the wallpaper (docs/ux/design-system.md §1).
 */
@Composable
private fun SummaryTag(item: PatientSummaryItem) {
    val colors = FamilyCareTheme.colors
    val critical = item.tone == PatientSummaryTone.CRITICAL
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (critical) colors.criticalContainer else colors.attentionContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = item.icon.image(),
            contentDescription = null,
            tint = if (critical) colors.criticalAccent else colors.attentionAccent,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = item.text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (critical) colors.onCriticalContainer else colors.onAttentionContainer,
        )
    }
}

/** The icon the shell draws for a module's summary fact. */
@Composable
private fun PatientSummaryIcon.image(): ImageVector = when (this) {
    PatientSummaryIcon.MEDICATION -> FcIcons.Medication
    PatientSummaryIcon.EVENT -> FcIcons.Event
    PatientSummaryIcon.STOCK -> FcIcons.Inventory2
    PatientSummaryIcon.WARNING -> FcIcons.WarningFilled
    PatientSummaryIcon.ERROR -> FcIcons.ErrorFilled
}

/**
 * The age the birth year implies, or null when it is not recorded. A future
 * year cannot produce a negative age on screen.
 */
internal fun patientAge(
    birthYear: Int?,
    currentYear: Int = LocalDate.now().year,
): Int? = birthYear?.let { year -> (currentYear - year).takeIf { it >= 0 } }

@StringRes
internal fun ReminderPolicy.labelRes(): Int = when (this) {
    ReminderPolicy.PRIMARY_WITH_ESCALATION -> R.string.patients_policy_primary_with_escalation
    ReminderPolicy.ALL_CAREGIVERS -> R.string.patients_policy_all_caregivers
    // Not offered, but the database enum has it: never show a raw server value.
    ReminderPolicy.ROTA -> R.string.patients_policy_unavailable
}
