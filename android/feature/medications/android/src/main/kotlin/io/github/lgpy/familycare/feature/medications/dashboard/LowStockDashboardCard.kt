package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.AttentionCard
import io.github.lgpy.familycare.core.ui.components.AttentionItem
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import org.koin.compose.viewmodel.koinViewModel

/**
 * Today's low-stock digest as the amber "needs attention" card (M5; D28; order
 * 50): up to five medications that are running low, fewest days left first,
 * then "+N more".
 *
 * Each line names the medication and says who it is for and how long it lasts,
 * matching the approved mockup: "Metformin 850 mg is running low" / "António
 * has 5 days left. Needs a prescription first." Tapping a line opens the
 * Shopping tab through [onNavigate], using the module's own route constant so
 * neither side imports the other (D5). With nothing low the card draws nothing.
 */
class LowStockDashboardCard : DashboardCard {

    override val order: Int = ORDER

    @Composable
    override fun Content(onNavigate: (String) -> Unit) {
        val viewModel = koinViewModel<LowStockViewModel>()
        val uiState by viewModel.uiState.collectAsState()

        if (!uiState.isVisible) return

        val openShopping = { onNavigate(MedicationsRoutes.SHOPPING) }

        AttentionCard(
            items = buildList {
                uiState.lines.forEach { line ->
                    add(
                        AttentionItem(
                            icon = Icons.Filled.ShoppingCart,
                            title = stringResource(
                                R.string.meds_attention_low_stock_title,
                                medicationLabel(line),
                            ),
                            supportingText = lowStockSupporting(line),
                            onClick = openShopping,
                        ),
                    )
                }
                if (uiState.more > 0) {
                    add(
                        AttentionItem(
                            icon = Icons.Filled.ShoppingCart,
                            title = stringResource(R.string.meds_low_stock_more, uiState.more),
                            onClick = openShopping,
                        ),
                    )
                }
            },
        )
    }

    /** "Metformin 850 mg": the name and its strength, never truncated. */
    @Composable
    private fun medicationLabel(line: LowStockLine): String =
        listOfNotNull(line.medicationName, line.strength).joinToString(" ")

    /** "António has 5 days left.", plus the prescription note when it applies. */
    @Composable
    private fun lowStockSupporting(line: LowStockLine): String {
        val resource = if (line.needsPrescription) {
            R.plurals.meds_attention_low_stock_prescription
        } else {
            R.plurals.meds_attention_low_stock
        }
        return pluralStringResource(resource, line.daysRemaining, line.patientName, line.daysRemaining)
    }

    companion object {

        /** Below needs-attention (0) and doses today (100), above the coming week. */
        const val ORDER: Int = 50
    }
}
