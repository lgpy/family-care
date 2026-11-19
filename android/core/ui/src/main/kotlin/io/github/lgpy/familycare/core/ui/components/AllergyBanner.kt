package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * The red allergy banner: the one critical message on a patient profile, and
 * always visible at the top of it.
 *
 * Uses the fixed critical colours from [FamilyCareTheme.colors], never the
 * wallpaper. The text is bold and never truncated: "Allergic to penicillin and
 * ibuprofen" wraps and the banner grows. The icon is decorative (the text says
 * it all), so it has no content description.
 *
 * Stateless: [text] is already localised by the caller.
 */
@Composable
fun AllergyBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = FamilyCareTheme.colors
    CompositionLocalProvider(LocalContentColor provides colors.onCriticalContainer) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.criticalContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = colors.criticalAccent,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(24.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@ScalePreviews
@Composable
private fun AllergyBannerPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                AllergyBanner(text = "Allergic to penicillin and ibuprofen")
            }
        }
    }
}
