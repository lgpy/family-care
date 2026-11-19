package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.theme.FamilyCareDimens
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.core.ui.theme.LocalPatientColors

/**
 * A patient's identity: a fixed-size circle in their [LocalPatientColors]
 * colour with their initial in the surface colour.
 *
 * The circle is dp (never scaled) and the initial is sized from the circle, so
 * a large system font cannot push the letter out of it. The initial is hidden
 * from TalkBack: the patient's name is always next to the avatar, and reading
 * a lone letter adds nothing.
 *
 * [name] is the name the avatar introduces; the initial is its first letter.
 * Pass the short name ("Maria", "Mom") where the screen shows a longer one
 * ("Grandma Maria"), as the mockup does.
 */
@Composable
fun PatientAvatar(
    patientId: PatientId,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = FamilyCareDimens.AvatarSize,
) {
    val color = LocalPatientColors.current(patientId)
    val initial = name.firstOrNull { it.isLetter() }?.uppercase() ?: "?"
    // Dp -> sp at the current font scale: the letter keeps the circle's size
    // instead of growing with the system font.
    val fontSize = with(LocalDensity.current) { size.toSp() * 0.45f }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.surface,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
        )
    }
}

@ScalePreviews
@Composable
private fun PatientAvatarPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PatientAvatar(PatientId("maria"), "Maria")
                PatientAvatar(PatientId("antonio"), "António")
                PatientAvatar(PatientId("mom"), "Mom")
                PatientAvatar(PatientId("you"), "You")
                PatientAvatar(PatientId("maria"), "Maria", size = 56.dp)
            }
        }
    }
}
