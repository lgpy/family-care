package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * The header of a top-level tab: the screen title, and an optional second line
 * (Today's date, a patient's name, a count).
 *
 * One header for every tab, so Today, Patients, Agenda, Shopping and More start
 * the same way: `headlineMedium` bold in `onSurface`, 2 dp above the subtitle,
 * which is `bodyLarge` in `onSurfaceVariant`. It adds no padding of its own:
 * the screen places it inside its 16 dp horizontal content padding
 * (docs/ux/design-system.md §3), and it spans that width so a long title wraps
 * instead of truncating.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        subtitle?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@ScalePreviews
@Composable
private fun ScreenHeaderPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            ScreenHeader(
                title = "Today",
                subtitle = "Thursday, 19 November",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
