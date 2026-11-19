package io.github.lgpy.familycare.feature.appointments.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.ReflowRow
import io.github.lgpy.familycare.core.ui.components.SectionHeader
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentKindTile
import io.github.lgpy.familycare.feature.appointments.ui.PreparationTagChip
import io.github.lgpy.familycare.feature.appointments.ui.formatAppointmentTime
import io.github.lgpy.familycare.feature.appointments.ui.labelRes
import io.github.lgpy.familycare.feature.appointments.ui.preparationChips
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

/**
 * The appointments module's contribution to Today: "Next 7 days" (order 200,
 * docs/domain/appointments.md "Screens"; docs/ux/mockups/screens-v2.html
 * `const today`).
 *
 * A section header with the calendar icon and an "Agenda" link, then up to five
 * appointments across every patient as day rows: the day number and short
 * weekday, a 40 dp tile with the appointment type's icon, the bold title, the
 * patient's avatar with the time and the place with its location icon, and the
 * preparation tag — amber with a "no food" icon when the preparation mentions
 * fasting, neutral otherwise. The row reflows through [ReflowRow], so at 200%
 * font size the tag moves below the text instead of being truncated.
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
            SectionHeader(
                title = stringResource(R.string.appointments_card_title),
                icon = FcIcons.Event,
                trailing = {
                    TextButton(onClick = { onNavigate(AppointmentsRoutes.AGENDA) }) {
                        Text(
                            text = stringResource(R.string.appointments_card_agenda),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )

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
                    AppointmentKindTile(kind = line.kind)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = line.title ?: stringResource(line.kind.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        MetaLine(line)
                    }
                }
            },
            action = {
                preparationChips(line.preparation).firstOrNull()?.let { chip ->
                    PreparationTagChip(chip)
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

/** The patient's avatar and the time, then the place with its location icon. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaLine(line: CardLine) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PatientAvatar(
                patientId = line.patientId,
                name = line.patientName,
                size = 18.dp,
            )
            Text(
                text = stringResource(
                    R.string.appointments_card_when,
                    line.patientName,
                    formatAppointmentTime(line.time),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        line.place?.takeIf { it.isNotBlank() }?.let { place ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = FcIcons.LocationOn,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = place,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
