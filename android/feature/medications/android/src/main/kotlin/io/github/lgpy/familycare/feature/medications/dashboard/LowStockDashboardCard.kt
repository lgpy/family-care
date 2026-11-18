package io.github.lgpy.familycare.feature.medications.dashboard

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import org.koin.compose.viewmodel.koinViewModel

/**
 * Today's "Low stock" card (M5; D28; order 50): up to five medications that are
 * running low, fewest days left first, then "+N more".
 *
 * It is a digest of the shopping list, not a second one: tapping anywhere on the
 * card asks the shell to open the Shopping tab through [onNavigate], using the
 * module's own route constant so neither side imports the other (D5). With
 * nothing low the card draws nothing at all.
 *
 * A line is generic enough for Today — "Grandpa · Metformin — About 2 days left"
 * — and the days come from the domain's stock summary, never from a recount here.
 */
class LowStockDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<LowStockViewModel>()
        val uiState by viewModel.uiState.collectAsState()

        if (!uiState.isVisible) return

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigate(MedicationsRoutes.SHOPPING) },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    // The same words as the badge on the medication screen.
                    text = stringResource(R.string.meds_stock_low_badge),
                    style = MaterialTheme.typography.titleMedium,
                )
                uiState.lines.forEach { line ->
                    Text(
                        text = lowStockLine(line),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (uiState.more > 0) {
                    Text(
                        text = stringResource(R.string.meds_low_stock_more, uiState.more),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    /** "Grandpa · Metformin — About 2 days left". */
    @Composable
    private fun lowStockLine(line: LowStockLine): String {
        val days = pluralStringResource(
            R.plurals.meds_stock_days_left,
            line.daysRemaining,
            line.daysRemaining,
        )
        return stringResource(
            R.string.meds_low_stock_line,
            line.patientName,
            line.medicationName,
            days,
        )
    }

    companion object {

        /** Below needs-attention (0) and doses today (100), above the coming week. */
        const val ORDER: Int = 50
    }
}
