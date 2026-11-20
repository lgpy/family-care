package io.github.lgpy.familycare.feature.medications.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import java.math.BigDecimal
import java.time.LocalTime

/**
 * The small parts the medications screens share after "screens v2"
 * (docs/ux/design-system.md §6): a section header with its icon, an inline
 * "icon + text" meta item, a time chip and the time-of-day icon.
 *
 * They live in the feature's `ui` package rather than in `core/ui` because only
 * the medications screens use them; everything that other features also show
 * (the pill picture, the meter, the avatar) stays in `core/ui`.
 */

/** A section header: icon beside a bold `titleMedium` label, never icon-only. */
@Composable
fun MedsSectionTitle(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * A decorative icon beside a piece of real text (patient, form, food note,
 * prescription). The label carries the meaning; the icon only aids scanning.
 */
@Composable
fun MedsMetaItem(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One dose time as a chip: its icon, `HH:MM` and, when the caller passes it, the
 * amount ("08:00 · 1"). The text wraps with the row; nothing is truncated.
 */
@Composable
fun MedsTimeChip(
    time: LocalTime,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    quantity: BigDecimal? = null,
) {
    val quantityText = if (quantity != null) {
        stringResource(R.string.meds_list_separator) + formatQuantity(quantity)
    } else {
        ""
    }
    val label = formatTime(time) + quantityText
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
            )
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
