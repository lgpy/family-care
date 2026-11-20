package io.github.lgpy.familycare.feature.medications.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.EmptyStateAction
import io.github.lgpy.familycare.core.ui.components.EmptyStateCard
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.components.PillTile
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import io.github.lgpy.familycare.core.ui.components.StockMeter
import io.github.lgpy.familycare.core.ui.components.stockMeterFraction
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.feature.medications.ui.MedsInstructionsItem
import io.github.lgpy.familycare.feature.medications.ui.MedsMetaItem
import io.github.lgpy.familycare.feature.medications.ui.regimenAmountTimesSummary
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A patient's medications in the "screens v2" shape (docs/ux/mockups/
 * screens-v2.html, `profileMeds`): one row per medicine with its pill picture,
 * name and strength, the amount and times of each dose, the food note when
 * there is one and a stock meter. Stopped medicines stay collapsed behind their
 * header.
 *
 * "Add medicine" exists for owners and caregivers only, the same rule the
 * repository enforces: an extended floating button, bottom-right, both here and
 * on the profile tab. On an empty profile the floating button gives way to the
 * empty state's own "Add their first medicine" action, so the first step is
 * where the user is already looking.
 *
 * The list itself ([PatientMedicationsSection]) is also the content of the
 * Medications tab of the patient profile (D29).
 */
@Composable
fun PatientMedicationsScreen(
    patientId: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenMedication: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientMedicationsViewModel>(
        parameters = { parametersOf(patientId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (uiState.canWrite && !uiState.patientMissing) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = {
                        Icon(imageVector = FcIcons.Add, contentDescription = null)
                    },
                    text = { Text(text = stringResource(R.string.meds_list_add)) },
                )
            }
        },
    ) { padding ->
        PatientMedicationsList(
            uiState = uiState,
            onToggleStopped = viewModel::toggleStopped,
            onOpenMedication = onOpenMedication,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = FcIcons.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        ScreenHeader(
                            title = stringResource(R.string.meds_list_title),
                            subtitle = uiState.patientName,
                        )
                    }
                }
            },
        )
    }
}

/**
 * The Medications tab of the patient profile (D29): the same list, embedded
 * below the profile's own header and tabs.
 *
 * It has no Scaffold of its own: the profile owns the screen and gives the tab
 * the space below its tabs, so "Add medicine" floats bottom-right of that
 * space, where it sits on the Patients and Agenda tabs too, instead of pushing
 * the list down from the top.
 */
@Composable
fun PatientMedicationsSection(
    patientId: String,
    onAdd: () -> Unit,
    onOpenMedication: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientMedicationsViewModel>(
        parameters = { parametersOf(patientId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    val canAdd = uiState.canWrite && !uiState.patientMissing
    Box(modifier = modifier.fillMaxSize()) {
        PatientMedicationsList(
            uiState = uiState,
            onToggleStopped = viewModel::toggleStopped,
            onOpenMedication = onOpenMedication,
            modifier = Modifier.fillMaxSize(),
            onAdd = onAdd.takeIf { canAdd },
        )
        // While the list is empty the empty state carries the action itself.
        if (canAdd && uiState.loaded && !uiState.isEmpty) {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(imageVector = FcIcons.Add, contentDescription = null) },
                text = { Text(text = stringResource(R.string.meds_list_add)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun PatientMedicationsList(
    uiState: PatientMedicationsUiState,
    onToggleStopped: () -> Unit,
    onOpenMedication: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (header != null) {
            item { header() }
        }

        if (!uiState.loaded) {
            item { CircularProgressIndicator() }
        } else if (uiState.patientMissing) {
            item {
                Text(
                    text = stringResource(R.string.meds_patient_not_available),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            if (uiState.isEmpty) {
                item {
                    EmptyStateCard(
                        title = stringResource(R.string.meds_list_empty),
                        action = onAdd?.let { add ->
                            EmptyStateAction(
                                label = stringResource(R.string.meds_list_add_first),
                                onClick = add,
                            )
                        },
                    )
                }
            }
            items(uiState.active, key = { it.medication.id.value }) { item ->
                MedicationRow(
                    item = item,
                    onClick = { onOpenMedication(item.medication.id.value) },
                )
            }
            if (uiState.stopped.isNotEmpty()) {
                item {
                    TextButton(onClick = onToggleStopped) {
                        Icon(
                            imageVector = FcIcons.History,
                            contentDescription = null,
                            modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.meds_list_stopped,
                                uiState.stopped.size,
                            ),
                        )
                    }
                }
            }
            if (uiState.stoppedExpanded) {
                items(uiState.stopped, key = { it.medication.id.value }) { item ->
                    MedicationRow(
                        item = item,
                        onClick = { onOpenMedication(item.medication.id.value) },
                    )
                }
            }
        }
    }
}

/**
 * One medicine: the 40 dp pill tile (the row's picture), the name and strength
 * as one wrapping title, the amount and times, the food note from the
 * medication's instructions and the stock meter with its icon — the filled
 * error symbol in amber when the stock is low.
 */
@Composable
private fun MedicationRow(item: PatientMedicationUi, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PillTile(
                look = item.medication.pillLook,
                size = 40.dp,
                contentDescription = null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.medication.strength
                        ?.let { "${item.medication.name} $it" }
                        ?: item.medication.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                item.item.currentRegimen?.let { regimen ->
                    MedsMetaItem(
                        icon = FcIcons.Schedule,
                        text = regimenAmountTimesSummary(regimen, item.medication.stockUnit),
                    )
                }

                item.medication.instructions?.let { note -> MedsInstructionsItem(note) }

                item.daysRemaining?.let { days ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StockMeter(
                            daysLeft = pluralStringResource(
                                if (item.low) {
                                    R.plurals.meds_stock_days_left_low
                                } else {
                                    R.plurals.meds_stock_days_left
                                },
                                days,
                                days,
                            ),
                            fraction = stockMeterFraction(days),
                            low = item.low,
                            modifier = Modifier.weight(1f),
                        )
                        // Only the low-stock warning carries an icon: a plain
                        // stock row does not need an unexplained box beside it
                        // (finding 15 of the UX audit).
                        if (item.low) {
                            Icon(
                                imageVector = FcIcons.ErrorFilled,
                                contentDescription = stringResource(R.string.meds_stock_low_badge),
                                tint = FamilyCareTheme.colors.attentionAccent,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
