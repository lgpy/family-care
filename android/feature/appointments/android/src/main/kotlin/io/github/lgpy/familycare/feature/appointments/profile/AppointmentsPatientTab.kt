package io.github.lgpy.familycare.feature.appointments.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.patient.PatientTab
import io.github.lgpy.familycare.core.ui.patient.PatientTabIcon
import io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes
import io.github.lgpy.familycare.feature.appointments.agenda.AgendaDay
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentDayHeader
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentLine
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Appointments tab of the patient profile (D29, docs/ux/screens.md §3).
 *
 * The module contributes it to the profile's extension point, so the app shell
 * opens this tab without importing the appointments screens (D5). The content is
 * that patient's upcoming appointments, then the past ones, drawn as the
 * Agenda's rows without the patient line (this tab has only one patient); "New
 * appointment" appears for owners and caregivers, the same rule the repository
 * enforces.
 */
class AppointmentsPatientTab : PatientTab {

    override val id: String = ID

    /** After medications' tab (10). */
    override val order: Int = 20

    override val titleRes: Int = R.string.appointments_tab_title

    /** Visits get the event symbol; filled while selected. */
    override val icon: PatientTabIcon = PatientTabIcon.EVENT

    @Composable
    override fun Content(patientId: PatientId, onNavigate: (String) -> Unit) {
        PatientAppointmentsSection(
            patientId = patientId.value,
            onAdd = { onNavigate(AppointmentsRoutes.newAppointment(patientId.value)) },
            onOpenAppointment = { appointmentId ->
                onNavigate(AppointmentsRoutes.appointmentDetail(appointmentId))
            },
        )
    }

    companion object {

        /** Stable id of the tab, unique across the contributions. */
        const val ID: String = "appointments"
    }
}

/**
 * The tab's content: the tonal "New appointment" action, then upcoming days and
 * past ones, cancelled appointments marked. It has no Scaffold of its own — the
 * profile owns the screen.
 */
@Composable
fun PatientAppointmentsSection(
    patientId: String,
    onAdd: () -> Unit,
    onOpenAppointment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientAppointmentsViewModel>(
        parameters = { parametersOf(patientId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (uiState.canWrite && !uiState.patientMissing) {
            item {
                FilledTonalButton(
                    onClick = onAdd,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(imageVector = FcIcons.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.appointments_new),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        when {
            !uiState.loaded -> item { CircularProgressIndicator() }
            uiState.patientMissing -> item {
                Text(
                    text = stringResource(R.string.appointments_patient_not_available),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            else -> {
                if (uiState.isEmpty) {
                    item {
                        Text(
                            text = stringResource(R.string.appointments_none_for_patient),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                patientDayGroups(uiState.upcomingDays, uiState.today, onOpenAppointment)
                if (uiState.pastDays.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.appointments_past),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    patientDayGroups(uiState.pastDays, uiState.today, onOpenAppointment)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.patientDayGroups(
    days: List<AgendaDay>,
    today: java.time.LocalDate,
    onOpenAppointment: (String) -> Unit,
) {
    days.forEach { day ->
        item(key = "day-${day.date}") {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppointmentDayHeader(date = day.date, today = today)
            }
        }
        items(day.entries, key = { "appointment-${it.appointment.id.value}" }) { entry ->
            AppointmentLine(
                entry = entry,
                showPatient = false,
                onClick = { onOpenAppointment(entry.appointment.id.value) },
            )
        }
    }
}
