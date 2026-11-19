package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme

/**
 * The widest a time label may be while it still sits beside its slots: 26% of
 * the rail's width, as measured in `docs/ux/mockups/scaling.html` (`fitRail`).
 * Above it, each time becomes a heading above its slots.
 */
internal const val TimeRailMaxTimeWidthFraction = 0.26f

/**
 * One time of day and its slots.
 *
 * @param time The label, e.g. "08:00".
 * @param secondary Optional second line under the time ("in 3 h 48 min"). It
 *   wraps under the time instead of widening the rail column.
 * @param slots The patients whose doses are due at [time].
 */
@Immutable
data class DoseTimeGroup(
    val time: String,
    val secondary: String? = null,
    val slots: List<DoseSlotUi>,
)

/**
 * Doses today as a time rail: each time in a left column beside its group of
 * [DoseSlot]s, or — when the time column would take more than
 * [TimeRailMaxTimeWidthFraction] of the width — each time as a heading above
 * its slots.
 *
 * The choice is made from measurements on every layout pass, never from
 * `fontScale`: at 200% font size the time text simply measures wider and the
 * rail stacks. The secondary line wraps under the time, so it never widens
 * the column, and no text is truncated.
 *
 * Stateless: groups and their slots are passed in whole.
 */
@Composable
fun TimeRail(
    groups: List<DoseTimeGroup>,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier = modifier.fillMaxWidth()) { constraints ->
        val bounded = constraints.hasBoundedWidth
        val availableWidth = constraints.maxWidth
        val maxHeight = constraints.maxHeight
        val gap = 12.dp.roundToPx()
        val groupGap = 16.dp.roundToPx()
        val labelTopPadding = 14.dp.roundToPx()
        val headingGap = 8.dp.roundToPx()

        val doneGroups = groups.map { group ->
            group.slots.isNotEmpty() && group.slots.all { it.state == DoseSlotState.DONE }
        }

        // 1. Measure the natural width of each time text. Only the time itself
        //    counts: the secondary line wraps under it.
        val widestTime = groups.mapIndexed { index, group ->
            subcompose(TimeProbeKey(index)) { TimeText(group.time, doneGroups[index]) }
                .maxOfOrNull { it.maxIntrinsicWidth(maxHeight) } ?: 0
        }.maxOrNull() ?: 0

        val railMode = bounded && widestTime <= availableWidth * TimeRailMaxTimeWidthFraction

        if (railMode) {
            val timeColumnWidth = minOf(widestTime, availableWidth)
            val slotsWidth = (availableWidth - timeColumnWidth - gap).coerceAtLeast(0)

            val labels = groups.mapIndexed { index, group ->
                subcompose(RailLabelKey(index)) {
                    RailTimeLabel(group.time, group.secondary, doneGroups[index])
                }.map {
                    it.measure(
                        Constraints(
                            minWidth = timeColumnWidth,
                            maxWidth = timeColumnWidth,
                            maxHeight = maxHeight,
                        ),
                    )
                }
            }
            val slotColumns = groups.mapIndexed { index, group ->
                subcompose(RailSlotsKey(index)) { SlotColumn(group.slots) }.map {
                    it.measure(
                        Constraints(
                            minWidth = slotsWidth,
                            maxWidth = slotsWidth,
                            maxHeight = maxHeight,
                        ),
                    )
                }
            }

            val groupHeights = groups.indices.map { index ->
                maxOf(
                    labelTopPadding + (labels[index].firstOrNull()?.height ?: 0),
                    slotColumns[index].firstOrNull()?.height ?: 0,
                )
            }
            val height = groupHeights.sum() + groupGap * (groups.size - 1).coerceAtLeast(0)

            layout(availableWidth, height.coerceAtLeast(0)) {
                var y = 0
                groups.indices.forEach { index ->
                    labels[index].firstOrNull()?.place(0, y + labelTopPadding)
                    slotColumns[index].firstOrNull()?.place(timeColumnWidth + gap, y)
                    y += groupHeights[index] + groupGap
                }
            }
        } else {
            val headingConstraints = if (bounded) {
                Constraints(maxWidth = availableWidth, maxHeight = maxHeight)
            } else {
                Constraints(maxWidth = Constraints.Infinity, maxHeight = maxHeight)
            }
            val headingPlaceables = groups.mapIndexed { index, group ->
                subcompose(StackedHeadingKey(index)) {
                    StackedTimeHeading(group.time, group.secondary, doneGroups[index])
                }.map { it.measure(headingConstraints) }
            }
            val slotConstraints = if (bounded) {
                Constraints(minWidth = availableWidth, maxWidth = availableWidth, maxHeight = maxHeight)
            } else {
                Constraints(maxWidth = Constraints.Infinity, maxHeight = maxHeight)
            }
            val slotPlaceables = groups.mapIndexed { index, group ->
                subcompose(StackedSlotsKey(index)) { SlotColumn(group.slots) }
                    .map { it.measure(slotConstraints) }
            }

            val width = if (bounded) {
                availableWidth
            } else {
                (headingPlaceables + slotPlaceables)
                    .mapNotNull { it.firstOrNull()?.width }
                    .maxOrNull() ?: 0
            }

            val groupHeights = groups.indices.map { index ->
                val headingHeight = headingPlaceables[index].firstOrNull()?.height ?: 0
                val slotsHeight = slotPlaceables[index].firstOrNull()?.height ?: 0
                headingHeight + headingGap + slotsHeight
            }
            val height = groupHeights.sum() + groupGap * (groups.size - 1).coerceAtLeast(0)

            layout(width, height.coerceAtLeast(0)) {
                var y = 0
                groups.indices.forEach { index ->
                    val headingHeight = headingPlaceables[index].firstOrNull()?.height ?: 0
                    headingPlaceables[index].firstOrNull()?.place(0, y)
                    slotPlaceables[index].firstOrNull()?.place(0, y + headingHeight + headingGap)
                    y += groupHeights[index] + groupGap
                }
            }
        }
    }
}

@Composable
private fun SlotColumn(slots: List<DoseSlotUi>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        slots.forEach { slot -> DoseSlot(slot) }
    }
}

@Composable
private fun TimeText(time: String, done: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = time,
        style = FamilyCareTheme.type.time,
        color = if (done) scheme.onSurfaceVariant.copy(alpha = 0.55f) else scheme.onSurface,
    )
}

/** The time column cell: the time, with its countdown wrapped underneath. */
@Composable
private fun RailTimeLabel(time: String, secondary: String?, done: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column {
        TimeText(time, done)
        secondary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant.copy(alpha = if (done) 0.55f else 1f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The stacked heading: the time, then the countdown beside it if it fits. */
@Composable
private fun StackedTimeHeading(time: String, secondary: String?, done: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TimeText(time, done)
        secondary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant.copy(alpha = if (done) 0.55f else 1f),
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 2.dp),
            )
        }
    }
}

private fun TimeProbeKey(index: Int) = "time-probe-$index"

private fun RailLabelKey(index: Int) = "rail-label-$index"

private fun RailSlotsKey(index: Int) = "rail-slots-$index"

private fun StackedHeadingKey(index: Int) = "stacked-heading-$index"

private fun StackedSlotsKey(index: Int) = "stacked-slots-$index"

@ScalePreviews
@Preview(name = "Rail · narrow", widthDp = 260, showBackground = true)
@Composable
private fun TimeRailPreview() {
    val scheme = MaterialTheme.colorScheme
    Surface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .padding(16.dp),
        ) {
            TimeRail(
                groups = listOf(
                    DoseTimeGroup(
                        time = "08:00",
                        slots = listOf(
                            DoseSlotUi(
                                patientId = PatientId("antonio"),
                                patientName = "António",
                                meta = "3 medicines",
                                state = DoseSlotState.DONE,
                                givenText = "Given by Mom at 08:04",
                            ),
                        ),
                    ),
                    DoseTimeGroup(
                        time = "13:00",
                        secondary = "in 3 h 48 min",
                        slots = listOf(
                            DoseSlotUi(
                                patientId = PatientId("maria"),
                                patientName = "Maria",
                                meta = "2 medicines",
                                state = DoseSlotState.NEXT,
                                actionLabel = "Give",
                                onAction = {},
                            ),
                        ),
                    ),
                    DoseTimeGroup(
                        time = "21:00",
                        slots = listOf(
                            DoseSlotUi(
                                patientId = PatientId("you"),
                                patientName = "You",
                                meta = "1 medicine",
                                state = DoseSlotState.UPCOMING,
                                actionLabel = "Give",
                                onAction = {},
                            ),
                            DoseSlotUi(
                                patientId = PatientId("mom"),
                                patientName = "Mom",
                                meta = "2 medicines",
                                state = DoseSlotState.UPCOMING,
                                actionLabel = "Give",
                                onAction = {},
                            ),
                        ),
                    ),
                ),
            )
        }
    }
}
