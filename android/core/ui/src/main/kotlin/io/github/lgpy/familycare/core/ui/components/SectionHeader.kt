package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * A section title inside a screen: its icon, the title and an optional link at
 * the end (docs/ux/design-system.md §3).
 *
 * The title is `titleMedium` bold in `onSurface` — never an accent colour — and
 * the icon is tinted with `primary` at the fixed 24 dp chrome size, decorative
 * beside the label. [trailing] is where a screen puts a text link such as the
 * Next 7 days card's "Agenda"; it is measured at its natural width, so the
 * title wraps before the link can be squeezed out.
 *
 * No padding of its own: the screen places it inside its 16 dp content padding.
 */
@Composable
fun SectionHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}

@ScalePreviews
@Composable
private fun SectionHeaderPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            SectionHeader(
                title = "Next 7 days",
                icon = FcIcons.Event,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                trailing = {
                    Text(
                        text = "Agenda",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
    }
}
