package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.theme.FamilyCareDimens
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * Stock left as a small bar plus a label ("5 days left").
 *
 * The bar is primary colour, or the fixed attention accent when [low]; in that
 * case the label is bold and the accent too. The bar keeps a sensible minimum
 * width and the label sits beside it while both fit; otherwise the label wraps
 * under the bar at full width. That decision is a measurement, not a font-size
 * threshold (see [ReflowRow]).
 *
 * The label is never truncated: a long phrase wraps instead.
 *
 * @param daysLeft The already-localised label ("5 days left").
 * @param fraction Filled share of the bar, 0..1.
 * @param low Whether the medication is below its low-stock threshold.
 */
@Composable
fun StockMeter(
    daysLeft: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    low: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = FamilyCareTheme.colors
    val clamped = fraction.coerceIn(0f, 1f)
    val fill = if (low) semantic.attentionAccent else scheme.primary

    val bar = @Composable {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FamilyCareDimens.StockBarHeight)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .background(fill, CircleShape),
            )
        }
    }
    val label = @Composable {
        Text(
            text = daysLeft,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (low) FontWeight.Bold else FontWeight.Normal,
            color = if (low) semantic.attentionAccent else scheme.onSurfaceVariant,
        )
    }

    Layout(
        contents = listOf(bar, label),
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        // The multi-content Layout overload gives one list per slot.
        val barMeasurable = measurables[0].first()
        val labelMeasurable = measurables[1].first()
        val spacingPx = 10.dp.roundToPx()
        val minBarPx = FamilyCareDimens.MinStockBarWidth.roundToPx()
        val barHeightPx = FamilyCareDimens.StockBarHeight.roundToPx()
        val available = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val maxHeight = constraints.maxHeight

        val labelNatural = labelMeasurable.maxIntrinsicWidth(maxHeight)
        val beside = bounded && reflowFits(available, minBarPx, labelNatural, spacingPx)

        if (beside) {
            val labelPlaceable = labelMeasurable.measure(
                Constraints(maxWidth = available, maxHeight = maxHeight),
            )
            val barWidth = (available - spacingPx - labelPlaceable.width).coerceAtLeast(0)
            val barPlaceable = barMeasurable.measure(
                Constraints(minWidth = barWidth, maxWidth = barWidth, maxHeight = maxHeight),
            )
            val height = maxOf(barHeightPx, labelPlaceable.height)
            layout(available, height) {
                barPlaceable.place(0, (height - barPlaceable.height) / 2)
                labelPlaceable.place(barWidth + spacingPx, (height - labelPlaceable.height) / 2)
            }
        } else {
            val width = if (bounded) available else maxOf(minBarPx, labelNatural)
            val barPlaceable = barMeasurable.measure(
                Constraints(minWidth = width, maxWidth = width, maxHeight = maxHeight),
            )
            val labelPlaceable = labelMeasurable.measure(
                Constraints(maxWidth = width, maxHeight = maxHeight),
            )
            val height = barPlaceable.height + spacingPx + labelPlaceable.height
            layout(width, height) {
                barPlaceable.place(0, 0)
                labelPlaceable.place(0, barPlaceable.height + spacingPx)
            }
        }
    }
}

@ScalePreviews
@Composable
private fun StockMeterPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StockMeter(daysLeft = "24 days left", fraction = 0.70f)
                StockMeter(daysLeft = "5 days left. Needs a prescription first.", fraction = 0.14f, low = true)
                StockMeter(daysLeft = "41 days left", fraction = 0.90f)
                StockMeter(daysLeft = "0 days left", fraction = 0f, low = true)
            }
        }
    }
}
