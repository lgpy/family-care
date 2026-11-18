package io.github.lgpy.familycare.feature.appointments.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes
import io.github.lgpy.familycare.feature.appointments.ui.formatAppointmentTime
import io.github.lgpy.familycare.feature.appointments.ui.labelRes
import java.time.format.DateTimeFormatter
import org.koin.compose.viewmodel.koinViewModel

/**
 * The appointments module's contribution to Today: "Next 7 days" (order 200,
 * docs/domain/appointments.md "Screens").
 *
 * Up to five appointments across every patient — "Tue 25 10:30 · Grandpa ·
 * Cardiologia" — plus "+N more". A line opens the appointment's detail screen
 * and the header opens the Agenda, both through [onNavigate], so the shell
 * imports neither the screens nor the module's view models (D5). With nothing
 * coming up the card draws nothing at all.
 */
class NextSevenDaysDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<NextSevenDaysViewModel>()
        val uiState by viewModel.uiState.collectAsState()

        if (!uiState.isVisible) return

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { onNavigate(AppointmentsRoutes.AGENDA) }) {
                    Text(
                        text = stringResource(R.string.appointments_card_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                uiState.lines.forEach { line ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onNavigate(AppointmentsRoutes.appointmentDetail(line.appointmentId))
                            }
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = lineHeadline(line),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = line.patientName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (uiState.extraCount > 0) {
                    Text(
                        text = stringResource(R.string.appointments_card_more, uiState.extraCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    /** "Tue 25 10:30 · Cardiologia": the day, the time and the title or kind. */
    @Composable
    private fun lineHeadline(line: CardLine): String {
        val locale = LocalConfiguration.current.locales[0]
        val day = line.date.format(
            DateTimeFormatter.ofPattern(DAY_PATTERN).withLocale(locale),
        )
        val label = line.title ?: stringResource(line.kind.labelRes())
        return day + " " + formatAppointmentTime(line.time) + " · " + label
    }

    private companion object {
        /** Order 200 is the "next seven days" slot the shell reserves. */
        const val ORDER = 200

        /** "Tue 25" / "ter 25": short weekday and day of the month. */
        const val DAY_PATTERN = "EEE d"
    }
}
