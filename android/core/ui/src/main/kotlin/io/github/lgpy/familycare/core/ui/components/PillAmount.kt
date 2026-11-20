package io.github.lgpy.familycare.core.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.medication.PillColor
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.domain.medication.PillShape
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import java.math.BigDecimal

/**
 * The most whole pills [PillAmount] draws. A larger dose would be a long row of
 * identical pictures that says less than the typed number does, so it is left
 * as text.
 */
internal const val MaxDrawnPills: Int = 6

/** How faint the un-taken part of a partial pill is drawn. */
private const val PillGhostAlpha: Float = 0.3f

/**
 * How much of the square box a pill's body occupies, vertically: every shape
 * draws its body inside the middle of the box, and 0.6 is the height a round
 * tablet uses (the most common look). The fill level is measured against this,
 * so a quarter of a tablet is a quarter of the pill rather than a quarter of
 * the empty space around it.
 */
private const val PillBodyFraction: Float = 0.6f

/** The whole pills to draw and the fraction of one after them. */
internal data class PillAmountParts(val whole: Int, val fraction: Float)

/**
 * How much a dose is, split into drawn pills: `1½` is one pill and half of the
 * next, `0.5` a half pill on its own. Null when nothing should be drawn (not a
 * positive amount, or more than [MaxDrawnPills] whole pills).
 */
internal fun pillAmountParts(quantity: BigDecimal): PillAmountParts? {
    if (quantity.signum() <= 0) return null
    val whole = quantity.toBigInteger().toInt()
    if (whole > MaxDrawnPills) return null
    val fraction = quantity.subtract(BigDecimal(whole)).toFloat()
    return PillAmountParts(whole = whole, fraction = fraction)
}

/**
 * Draws a dose as the pills it is: one drawn pill per whole unit from the
 * medicine's own [look] (D33), and a partly filled pill for the fraction — a
 * half tablet shows its lower half solid and the upper half faint, like a glass
 * being filled.
 *
 * It is an illustration beside the typed amount, not a control, and the number
 * stays the authority: the row is drawn for the amounts that read at a glance
 * (up to [MaxDrawnPills] pills) and left out above that. Nothing is drawn for a
 * medicine with no pill look.
 */
@Composable
fun PillAmount(
    look: PillLook?,
    quantity: BigDecimal,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    if (look == null) return
    val parts = pillAmountParts(quantity) ?: return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(parts.whole) {
            PillImage(look = look, size = size, contentDescription = null)
        }
        if (parts.fraction > 0f) {
            PartialPill(look = look, fraction = parts.fraction, size = size)
        }
    }
}

/** One pill whose solid part is the bottom [fraction] of it. */
@Composable
private fun PartialPill(look: PillLook, fraction: Float, size: Dp) {
    Box(modifier = Modifier.size(size)) {
        PillImage(
            look = look,
            size = size,
            contentDescription = null,
            modifier = Modifier.alpha(PillGhostAlpha),
        )
        PillImage(
            look = look,
            size = size,
            contentDescription = null,
            modifier = Modifier.drawWithContent {
                // Filled from the bottom up: 0.25 is the lower quarter of the
                // pill, 0.5 its lower half, 0.75 all but the top quarter.
                val clamped = fraction.coerceIn(0f, 1f)
                val top = 0.5f + PillBodyFraction / 2f - clamped * PillBodyFraction
                clipRect(top = this.size.height * top) {
                    this@drawWithContent.drawContent()
                }
            },
        )
    }
}

private val PreviewLook = PillLook(shape = PillShape.OBLONG, color = PillColor.WHITE, score = true)
private val PreviewCapsule = PillLook(
    shape = PillShape.CAPSULE,
    color = PillColor.PURPLE,
    color2 = PillColor.PINK,
)

@Preview(name = "Pill amounts light", widthDp = 320, showBackground = true)
@Preview(
    name = "Pill amounts dark",
    widthDp = 320,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PillAmountPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                    "0.25" to PreviewLook,
                    "0.5" to PreviewLook,
                    "1" to PreviewLook,
                    "1.5" to PreviewLook,
                    "2" to PreviewCapsule,
                    "3.75" to PreviewCapsule,
                ).forEach { (amount, look) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(text = amount, style = MaterialTheme.typography.labelMedium)
                        PillAmount(look = look, quantity = BigDecimal(amount))
                    }
                }
                Text(text = "Above six pills: text only", style = MaterialTheme.typography.labelMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = "7", style = MaterialTheme.typography.labelMedium)
                    // Draws nothing, on purpose.
                    PillAmount(look = PreviewLook, quantity = BigDecimal("7"))
                }
            }
        }
    }
}
