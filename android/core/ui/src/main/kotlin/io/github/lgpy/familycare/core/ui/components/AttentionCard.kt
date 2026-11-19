package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import io.github.lgpy.familycare.core.ui.theme.FamilyCareDimens
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * One thing that needs attention inside an [AttentionCard].
 *
 * @param icon The kind (warning for an unconfirmed dose, a box for low stock).
 *   Tinted with the attention accent.
 * @param title The bold one-liner ("Maria's 08:00 medicines aren't confirmed").
 * @param supportingText Optional second line ("4 medicines. Mom was reminded at
 *   08:00, then you at 08:30.").
 * @param action Optional button under this item, full width.
 */
data class AttentionItem(
    val icon: ImageVector,
    val title: String,
    val supportingText: String? = null,
    val action: AttentionAction? = null,
)

/**
 * The button of an [AttentionItem]. Full width, accent background, container
 * text colour (the mockup's `.btn.attn-btn`).
 */
data class AttentionAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The amber "needs attention" card: the only coloured block at the top of
 * Today.
 *
 * Fixed attention colours from [FamilyCareTheme.colors] — these never follow
 * the wallpaper, so an alert looks the same on every phone. Holds one or more
 * [items] separated by a divider tinted with the container's text colour at
 * 18%. Text never truncates: a long medicine name or patient name wraps and
 * the card grows.
 *
 * Stateless: every action is passed in as an [AttentionAction].
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
            .padding(FamilyCareDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onAttentionContainer) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        color = colors.onAttentionContainer.copy(alpha = 0.18f),
                    )
                }
                AttentionItemContent(item)
            }
        }
    }
}

@Composable
private fun AttentionItemContent(item: AttentionItem) {
    val colors = FamilyCareTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = colors.attentionAccent,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(24.dp),
            )
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                item.supportingText?.let { supporting ->
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 3.dp),
                        color = LocalContentColor.current.copy(alpha = 0.85f),
                    )
                }
            }
        }
        item.action?.let { action ->
            Button(
                onClick = action.onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FamilyCareDimens.MinTouchTarget),
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.attentionAccent,
                    contentColor = colors.attentionContainer,
                ),
            ) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
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
                            icon = Icons.Filled.Warning,
                            title = "Maria's 08:00 medicines aren't confirmed",
                            supportingText = "4 medicines. Mom was reminded at 08:00, then you at 08:30.",
                            action = AttentionAction(label = "Give all 4", onClick = {}),
                        ),
                        AttentionItem(
                            icon = Icons.Filled.ShoppingCart,
                            title = "Metformin 850 mg is running low",
                            supportingText = "António has 5 days left. Needs a prescription first.",
                        ),
                    ),
                )
            }
        }
    }
}
