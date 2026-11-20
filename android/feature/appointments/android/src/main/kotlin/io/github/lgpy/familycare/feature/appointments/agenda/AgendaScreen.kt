package io.github.lgpy.familycare.feature.appointments.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import io.github.lgpy.familycare.core.ui.components.EmptyStateAction
import io.github.lgpy.familycare.core.ui.components.EmptyStateCard
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import io.github.lgpy.familycare.core.ui.components.SectionHeader
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentDayHeader
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentLine
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Agenda tab (docs/domain/appointments.md "Screens"; docs/ux/mockups/
 * screens-v2.html `const agenda`): every patient's appointments grouped by day,
 * a filter chip per patient, a History button that shows or hides the past, and
 * — for owners and caregivers — an extended "New" button.
 *
 * The day headers come from the appointments' own local dates, so an
 * appointment abroad is grouped by the day it happens there.
 */
@Composable
fun AgendaScreen(
    onOpenAppointment: (String) -> Unit,
    onNewAppointment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<AgendaViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        // A tab inside the app's Scaffold: the outer one already handles the
        // system bars, so this one must not add the status bar again.
        contentWindowInsets = WindowInsets(0),
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (uiState.canWrite) {
                ExtendedFloatingActionButton(
                    onClick = onNewAppointment,
                    icon = {
                        Icon(
                            imageVector = FcIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    text = { Text(text = stringResource(R.string.appointments_agenda_new)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Vertical only: the patient chips below span the whole screen so a
            // chip scrolls to the edge instead of being clipped inside the
            // padding. Every other item pads itself by 16 dp.
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScreenHeader(
                        title = stringResource(R.string.appointments_agenda_title),
                        modifier = Modifier.weight(1f),
                    )
                    // The same "Show past" toggle as before, now the History
                    // button of the mockup's top bar; the label stays its
                    // description, so nothing is icon-only (design system §6).
                    IconButton(onClick = viewModel::togglePast) {
                        Icon(
                            imageVector = FcIcons.History,
                            contentDescription = stringResource(
                                if (uiState.showPast) {
                                    R.string.appointments_hide_past
                                } else {
                                    R.string.appointments_show_past
                                },
                            ),
                            tint = if (uiState.showPast) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            if (uiState.patients.size > 1) {
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = uiState.selectedPatientId == null,
                                onClick = { viewModel.selectPatient(null) },
                                label = { Text(stringResource(R.string.appointments_everyone)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = FcIcons.Groups,
                                        contentDescription = null,
                                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                                    )
                                },
                            )
                        }
                        items(uiState.patients, key = { it.id }) { patient ->
                            FilterChip(
                                selected = uiState.selectedPatientId == patient.id,
                                onClick = { viewModel.selectPatient(patient.id) },
                                label = { Text(patient.name) },
                                leadingIcon = {
                                    PatientAvatar(
                                        patientId = PatientId(patient.id),
                                        name = patient.name,
                                        size = 22.dp,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (!uiState.loaded) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(horizontal = 16.dp))
                }
            } else {
                if (uiState.isEmpty) {
                    item {
                        // The same New action as the floating button, inside the
                        // empty state, so an empty Agenda says how to fill it.
                        EmptyStateCard(
                            title = stringResource(R.string.appointments_empty),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            action = if (uiState.canWrite) {
                                EmptyStateAction(
                                    label = stringResource(R.string.appointments_agenda_new),
                                    onClick = onNewAppointment,
                                )
                            } else {
                                null
                            },
                        )
                    }
                }

                dayGroups(
                    days = uiState.upcomingDays,
                    today = uiState.today,
                    onOpenAppointment = onOpenAppointment,
                )

                if (uiState.pastDays.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.appointments_past),
                            icon = FcIcons.ManageHistory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    dayGroups(
                        days = uiState.pastDays,
                        today = uiState.today,
                        onOpenAppointment = onOpenAppointment,
                    )
                }
            }
        }
    }
}

/** Appends one day label per day and its rows to the enclosing LazyColumn. */
private fun androidx.compose.foundation.lazy.LazyListScope.dayGroups(
    days: List<AgendaDay>,
    today: java.time.LocalDate,
    onOpenAppointment: (String) -> Unit,
) {
    days.forEach { day ->
        item(key = "day-${day.date}") {
            AppointmentDayHeader(
                date = day.date,
                today = today,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(day.entries, key = { "appointment-${it.appointment.id.value}" }) { entry ->
            AppointmentLine(
                entry = entry,
                showPatient = true,
                onClick = { onOpenAppointment(entry.appointment.id.value) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
