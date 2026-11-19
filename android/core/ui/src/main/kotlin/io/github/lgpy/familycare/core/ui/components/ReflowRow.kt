package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * The pure fit decision behind [ReflowRow]: may the content and the action
 * share one line?
 *
 * Widths are pixels as measured at layout time. The function is separate (and
 * internal, so unit-testable) because this decision must never be made from
 * `fontScale`: at 200% the content simply measures too wide and the layout
 * reflows by itself.
 *
 * @return true when content, spacing and action fit within [availableWidth].
 *   An unbounded width ([Constraints.Infinity]) always fits; negative or
 *   nonsensical measurements never do.
 */
internal fun reflowFits(
    availableWidth: Int,
    contentWidth: Int,
    actionWidth: Int,
    spacing: Int,
): Boolean {
    if (availableWidth <= 0 || contentWidth < 0 || actionWidth < 0 || spacing < 0) return false
    return contentWidth.toLong() + spacing.toLong() + actionWidth.toLong() <= availableWidth.toLong()
}

/**
 * Content and its action on one line while both fit; otherwise the action
 * drops below and fills the width.
 *
 * This is the mockup's `.reflow` pattern, used by dose slots, card headers and
 * the Today app bar:
 *
 * - **Side by side:** the action is end-aligned and vertically centred, and
 *   the content takes the remaining width.
 * - **Stacked:** the content is full width and the action is below, full
 *   width (a button is then full width, not left-aligned).
 *
 * The decision is a measurement of the content's maximum intrinsic width
 * against the action's width — never a font-scale or width breakpoint. When
 * the content changes size (a longer medicine name, a larger system font) it
 * reflows without any code knowing the font size.
 *
 * Stateless: the caller owns the action's behaviour.
 *
 * @param spacing Gap between content and action, and between the stacked rows.
 * @param content The flexible part (a patient row, a title block).
 * @param action The button or control; rendered after the content. An empty
 *   action is supported (the content then uses the whole width).
 */
@Composable
fun ReflowRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable () -> Unit,
    action: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(content, action),
        modifier = modifier,
    ) { measurables, constraints ->
        // Each slot must emit at most one layout node; the multi-content
        // overload hands one list per slot.
        val contentMeasurable = measurables[0].firstOrNull()
        val actionMeasurable = measurables[1].firstOrNull()
        if (contentMeasurable == null) {
            layout(0, 0) {}
        } else {
            val spacingPx = spacing.roundToPx()
            val bounded = constraints.hasBoundedWidth
            val available = constraints.maxWidth
            val maxHeight = constraints.maxHeight

            val contentNatural = contentMeasurable.maxIntrinsicWidth(maxHeight)
            val actionNatural = actionMeasurable?.maxIntrinsicWidth(maxHeight) ?: 0

            // Decide from intrinsics first and measure each child exactly once:
            // Compose throws if a measurable is measured twice in one pass
            // (this crashed the app at 200% font, where rows stack).
            val actionIsEmpty = actionMeasurable == null || actionNatural == 0
            val sideBySide = !actionIsEmpty &&
                (!bounded || reflowFits(available, contentNatural, actionNatural, spacingPx))

            if (actionIsEmpty) {
                // No action: the content owns the whole row (and full width).
                val width = if (bounded) available else contentNatural
                val placeable = contentMeasurable.measure(
                    Constraints(minWidth = width, maxWidth = width, maxHeight = maxHeight),
                )
                layout(width, placeable.height) { placeable.place(0, 0) }
            } else if (sideBySide) {
                val actionPlaceable = actionMeasurable!!.measure(
                    Constraints(
                        maxWidth = if (bounded) available else Constraints.Infinity,
                        maxHeight = maxHeight,
                    ),
                )
                val contentWidth = if (bounded) {
                    (available - spacingPx - actionPlaceable.width).coerceAtLeast(0)
                } else {
                    contentNatural
                }
                val contentPlaceable = contentMeasurable.measure(
                    Constraints(minWidth = contentWidth, maxWidth = contentWidth, maxHeight = maxHeight),
                )
                val width = if (bounded) available else contentPlaceable.width + spacingPx + actionPlaceable.width
                val height = maxOf(contentPlaceable.height, actionPlaceable.height)
                layout(width, height) {
                    contentPlaceable.place(0, (height - contentPlaceable.height) / 2)
                    actionPlaceable.place(
                        width - actionPlaceable.width,
                        (height - actionPlaceable.height) / 2,
                    )
                }
            } else {
                // Stacked: content full width, action below it, full width.
                val width = if (bounded) available else maxOf(contentNatural, actionNatural)
                val contentPlaceable = contentMeasurable.measure(
                    Constraints(minWidth = width, maxWidth = width, maxHeight = maxHeight),
                )
                val stackedAction = actionMeasurable!!.measure(
                    Constraints(minWidth = width, maxWidth = width, maxHeight = maxHeight),
                )
                val height = contentPlaceable.height + spacingPx + stackedAction.height
                layout(width, height) {
                    contentPlaceable.place(0, 0)
                    stackedAction.place(0, contentPlaceable.height + spacingPx)
                }
            }
        }
    }
}

@ScalePreviews
@Preview(name = "Reflow · narrow", widthDp = 260, showBackground = true)
@Composable
private fun ReflowRowPreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ReflowRow(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        Column {
                            Text(
                                text = "Maria",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "2 medicines, 08:00",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    action = { Button(onClick = {}) { Text("Give") } },
                )
                ReflowRow(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Thursday 19 November",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                    action = { Button(onClick = {}) { Text("All patients") } },
                )
            }
        }
    }
}
