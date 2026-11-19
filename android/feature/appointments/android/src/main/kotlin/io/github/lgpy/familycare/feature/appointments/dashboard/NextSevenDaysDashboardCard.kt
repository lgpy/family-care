package io.github.lgpy.familycare.feature.appointments.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.ReflowRow
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes
import io.github.lgpy.familycare.feature.appointments.ui.formatAppointmentTime
import io.github.lgpy.familycare.feature.appointments.ui.labelRes
import java.time.LocalDate
import java.time.format.TextStyle
import org.koin.compose.viewmodel.koinViewModel

/**
 * The appointments module's contribution to Today: "Next 7 days" (order 200,
 * docs/domain/appointments.md "Screens"; docs/ux/mockups/scaling.html
 * "upcoming").
 *
 * Up to five appointments across every patient as day rows: the day number and
 * short weekday, the bold title, "Patient at HH:MM, Place" underneath and the
 * preparation flag ("Fasting") in the attention accent. Rows sit on
 * `surfaceContainerLow` with 2 dp gaps inside a 20 dp rounded container. The
 * row reflows through [ReflowRow], so at 200% font size the flag moves below
 * the text instead of being truncated.
 *
 * A row opens the appointment's detail screen and the header opens the Agenda,
 * both through [onNavigate], so the shell imports neither the screens nor the
 * module's view models (D5). With nothing coming up the card draws nothing.
 */
class NextSevenDaysDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<NextSevenDaysViewModel>()
        val uiState by viewModel.uiState.collectAsState()

        if (!uiState.isVisible) return

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onNavigate(AppointmentsRoutes.AGENDA) }) {
                Text(
                    text = stringResource(R.string.appointments_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // A section title like "Doses today", not an accent link;
                    // the button is only the tap target that opens the Agenda.
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                uiState.lines.forEach { line ->
                    UpcomingRow(
                        line = line,
                        onClick = {
                            onNavigate(AppointmentsRoutes.appointmentDetail(line.appointmentId))
                        },
                    )
                }
            }

            if (uiState.extraCount > 0) {
                Text(
                    text = stringResource(R.string.appointments_card_more, uiState.extraCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    private companion object {
        /** Order 200 is the "next seven days" slot the shell reserves. */
        const val ORDER = 200
    }
}

/** One appointment row of the "upcoming" list. */
@Composable
private fun UpcomingRow(
    line: CardLine,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ReflowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            spacing = 12.dp,
            content = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DayBlock(line.date)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = line.title ?: stringResource(line.kind.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = lineDetail(line),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            action = {
                line.preparation?.takeIf { it.isNotBlank() }?.let { preparation ->
                    Text(
                        text = preparation,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = FamilyCareTheme.colors.attentionAccent,
                    )
                }
            },
        )
    }
}

/** "23" over "Mon": the mockup's day column. */
@Composable
private fun DayBlock(date: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    val weekday = remember(date, locale) {
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    }

    Column(
        modifier = Modifier.widthIn(min = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = FamilyCareTheme.type.timeSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = weekday,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** "António at 08:30, Lab Joaquim Chaves", or just the time without a place. */
@Composable
private fun lineDetail(line: CardLine): String {
    val time = formatAppointmentTime(line.time)
    return line.place?.takeIf { it.isNotBlank() }?.let { place ->
        stringResource(R.string.appointments_card_line, line.patientName, time, place)
    } ?: stringResource(R.string.appointments_card_line_no_place, line.patientName, time)
}
