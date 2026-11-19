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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
 * chevron. The action moves below the text, start-aligned, as soon as the two
 * no longer fit side by side (measured, never a font-scale switch). Fixed
 * attention colours from [FamilyCareTheme.colors], so an alert looks the same on
 * every phone whatever the wallpaper.
 *
 * Text never truncates: a long medicine name or patient name wraps at word
 * boundaries and the row grows. Stateless: every action is passed in as an
 * [AttentionAction].
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
    Column(
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
    ) {
        AttentionRowLayout(
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TileGap),
                ) {
                    Box(
                        modifier = Modifier
                            .size(TileSize)
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
                                modifier = Modifier.padding(top = 2.dp),
                                color = LocalContentColor.current.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            },
            action = {
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
            },
        )
    }
}

/** The 36 dp rounded icon tile of a row. */
private val TileSize = 36.dp

/** The gap between the icon tile and the text it introduces. */
private val TileGap = 12.dp

/**
 * The row's content (tile + text) and its action (button or chevron), laid out
 * by measurement: side by side while both fit, the action below the text
 * otherwise ([ReflowRow]'s rule, with one difference).
 *
 * Unlike [ReflowRow], the stacked action is **start-aligned with the text**
 * (indented past the icon tile) rather than full width: an attention row's
 * action is a small pill or a chevron, and stretching it across the card would
 * make it a different control.
 *
 * The decision is the content's maximum intrinsic width — the widest the words
 * can be without wrapping — against the action's, never `fontScale`. When it
 * stacks, the text keeps the whole row, so Compose only breaks a word that is
 * itself wider than the card (design-system §6: reflow by measurement).
 */
/**
 * Width an attention row's content needs beside its action: the icon indent
 * plus the text wrapped onto about two lines (with 15% slack for uneven
 * breaks), and never less than the content's longest word.
 */
internal fun attentionContentNeed(natural: Int, longestWord: Int, indent: Int): Int {
    val text = (natural - indent).coerceAtLeast(0)
    val twoLines = indent + (text * 0.575f).toInt()
    return maxOf(twoLines, longestWord)
}

@Composable
private fun AttentionRowLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = TileGap,
    content: @Composable () -> Unit,
    action: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(content, action),
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val contentMeasurable = measurables[0].firstOrNull()
        val actionMeasurable = measurables[1].firstOrNull()
        if (contentMeasurable == null) {
            layout(0, 0) {}
        } else {
            val spacingPx = spacing.roundToPx()
            val indentPx = (TileSize + TileGap).roundToPx()
            val available = constraints.maxWidth
            val maxHeight = constraints.maxHeight

            val contentNatural = contentMeasurable.maxIntrinsicWidth(maxHeight)
            val actionNatural = actionMeasurable?.maxIntrinsicWidth(maxHeight) ?: 0
            val actionIsEmpty = actionMeasurable == null || actionNatural == 0
            // The text may take two lines before the action moves below it, but
            // never less than its longest word (so words never break inside).
            val contentNeed = attentionContentNeed(
                natural = contentNatural,
                longestWord = contentMeasurable.minIntrinsicWidth(maxHeight),
                indent = indentPx,
            )
            val sideBySide = !actionIsEmpty &&
                reflowFits(available, contentNeed, actionNatural, spacingPx)

            if (actionIsEmpty) {
                val contentPlaceable = contentMeasurable.measure(
                    Constraints(minWidth = available, maxWidth = available, maxHeight = maxHeight),
                )
                layout(available, contentPlaceable.height) { contentPlaceable.place(0, 0) }
            } else if (sideBySide) {
                val actionPlaceable = actionMeasurable!!.measure(
                    Constraints(maxWidth = available, maxHeight = maxHeight),
                )
                val contentWidth = (available - spacingPx - actionPlaceable.width).coerceAtLeast(0)
                val contentPlaceable = contentMeasurable.measure(
                    Constraints(minWidth = contentWidth, maxWidth = contentWidth, maxHeight = maxHeight),
                )
                val height = maxOf(contentPlaceable.height, actionPlaceable.height)
                layout(available, height) {
                    contentPlaceable.place(0, (height - contentPlaceable.height) / 2)
                    actionPlaceable.place(
                        available - actionPlaceable.width,
                        (height - actionPlaceable.height) / 2,
                    )
                }
            } else {
                // Stacked: the text owns the width, the action starts under it.
                val contentPlaceable = contentMeasurable.measure(
                    Constraints(minWidth = available, maxWidth = available, maxHeight = maxHeight),
                )
                val stackedAction = actionMeasurable!!.measure(
                    Constraints(
                        maxWidth = (available - indentPx).coerceAtLeast(0),
                        maxHeight = maxHeight,
                    ),
                )
                val height = contentPlaceable.height + spacingPx + stackedAction.height
                layout(available, height) {
                    contentPlaceable.place(0, 0)
                    stackedAction.place(indentPx, contentPlaceable.height + spacingPx)
                }
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
                            icon = FcIcons.MedicationFilled,
                            title = "Maria’s 08:00 not confirmed",
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
