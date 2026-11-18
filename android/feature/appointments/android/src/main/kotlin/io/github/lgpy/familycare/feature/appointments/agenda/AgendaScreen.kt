package io.github.lgpy.familycare.feature.appointments.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentLine
import io.github.lgpy.familycare.feature.appointments.ui.dayLabel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Agenda tab (docs/domain/appointments.md "Screens"): every patient's
 * appointments grouped by day, filter chips per patient, a "Show past" toggle
 * and — for owners and caregivers — a floating "New appointment" button.
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
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (uiState.canWrite) {
                FloatingActionButton(onClick = onNewAppointment) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.appointments_new),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.appointments_agenda_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            if (uiState.patients.size > 1) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = uiState.selectedPatientId == null,
                                onClick = { viewModel.selectPatient(null) },
                                label = { Text(stringResource(R.string.appointments_all_patients)) },
                            )
                        }
                        items(uiState.patients, key = { it.id }) { patient ->
                            FilterChip(
                                selected = uiState.selectedPatientId == patient.id,
                                onClick = { viewModel.selectPatient(patient.id) },
                                label = { Text(patient.name) },
                            )
                        }
                    }
                }
            }

            item {
                TextButton(onClick = viewModel::togglePast) {
                    Text(
                        text = stringResource(
                            if (uiState.showPast) {
                                R.string.appointments_hide_past
                            } else {
                                R.string.appointments_show_past
                            },
                        ),
                    )
                }
            }

            if (!uiState.loaded) {
                item { CircularProgressIndicator() }
            } else {
                if (uiState.isEmpty) {
                    item {
                        Text(
                            text = stringResource(R.string.appointments_empty),
                            style = MaterialTheme.typography.bodyLarge,
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
                        Text(
                            text = stringResource(R.string.appointments_past),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp),
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

/** Appends one header per day and its rows to the enclosing LazyColumn. */
private fun androidx.compose.foundation.lazy.LazyListScope.dayGroups(
    days: List<AgendaDay>,
    today: java.time.LocalDate,
    onOpenAppointment: (String) -> Unit,
) {
    days.forEach { day ->
        item(key = "day-${day.date}") {
            Text(
                text = dayLabel(day.date, today),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
            )
        }
        items(day.entries, key = { "appointment-${it.appointment.id.value}" }) { entry ->
            AppointmentLine(
                entry = entry,
                showPatient = true,
                onClick = { onOpenAppointment(entry.appointment.id.value) },
            )
            HorizontalDivider()
        }
    }
}
