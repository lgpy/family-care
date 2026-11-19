package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.theme.FamilyCareDimens
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * One thing that needs attention inside an [AttentionCard].
 *
 * @param icon The kind (medicines for an unconfirmed dose, a box for low stock,
 *   a crossed bell for broken reminders). It sits in a 36 dp rounded tile
 *   tinted with the attention accent.
 * @param title The bold one-liner ("Maria's 08:00 medicines aren't confirmed").
 * @param supportingText Optional second line ("4 medicines", "António, 5 days
 *   left · needs prescription"). It wraps; it is never truncated.
 * @param action Optional small button at the end of the row ("Give"). Its
 *   [AttentionAction.onClick] runs instead of [onClick] when tapped.
 * @param onClick What tapping the row does (opens the dose slot, the shopping
 *   list or the reminders setup), null when the row is not a link. A row with
 *   an [action] shows the button; a row without one shows a chevron.
 */
data class AttentionItem(
    val icon: ImageVector,
    val title: String,
    val supportingText: String? = null,
    val action: AttentionAction? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * The button of an [AttentionItem]: compact, accent background, container text
 * colour (the mockup's `.btn.sm.attn-b`). It keeps a 48 dp touch target even
 * though it is visually small.
 */
data class AttentionAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The amber "needs attention" card: the only coloured block at the top of
 * Today (docs/ux/mockups/screens-v2.html `const today`).
 *
 * One light container with compact rows separated by thin dividers. Each row is
 * a 36 dp rounded icon tile (attention accent at 18%), the bold title with its
 * one-line detail, and — at the end — either a small action button or a
 * chevron. Fixed attention colours from [FamilyCareTheme.colors], so an alert
 * looks the same on every phone whatever the wallpaper.
 *
 * Text never truncates: a long medicine name or patient name wraps and the row
 * grows. Stateless: every action is passed in as an [AttentionAction].
 */
@Composable
fun AttentionCard(
    items: List<AttentionItem>,
    modifier: Modifier = Modifier,
) {
    val colors = FamilyCareTheme.colors
    val shape = RoundedCornerShape(FamilyCareDimens.CardRadius)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.attentionContainer, shape)
            .padding(vertical = 6.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onAttentionContainer) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        color = colors.onAttentionContainer.copy(alpha = 0.14f),
                    )
                }
                AttentionRowContent(item)
            }
        }
    }
}

@Composable
private fun AttentionRowContent(item: AttentionItem) {
    val colors = FamilyCareTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(onClick = item.onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.attentionAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = colors.attentionAccent,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            item.supportingText?.let { supporting ->
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                    color = LocalContentColor.current.copy(alpha = 0.85f),
                )
            }
        }
        val action = item.action
        if (action != null) {
            Button(
                onClick = action.onClick,
                modifier = Modifier.heightIn(min = FamilyCareDimens.MinTouchTarget),
                shape = RoundedCornerShape(percent = 50),
                contentPadding = PaddingValues(horizontal = 18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.attentionAccent,
                    contentColor = colors.attentionContainer,
                ),
            ) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else if (item.onClick != null) {
            Icon(
                imageVector = FcIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@ScalePreviews
@Composable
private fun AttentionCardPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                AttentionCard(
                    items = listOf(
                        AttentionItem(
                            icon = FcIcons.MedicationFilled,
                            title = "Maria's 08:00 medicines aren't confirmed",
                            supportingText = "4 medicines",
                            action = AttentionAction(label = "Give all 4", onClick = {}),
                            onClick = {},
                        ),
                        AttentionItem(
                            icon = FcIcons.Inventory2,
                            title = "Metformin running low",
                            supportingText = "António, 5 days left · needs prescription",
                            onClick = {},
                        ),
                        AttentionItem(
                            icon = FcIcons.NotificationsOff,
                            title = "Reminders need attention",
                            supportingText = "Check: battery exemption.",
                            onClick = {},
                        ),
                    ),
                )
            }
        }
    }
}
