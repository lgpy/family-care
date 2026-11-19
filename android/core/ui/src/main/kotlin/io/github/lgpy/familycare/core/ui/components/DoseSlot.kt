package io.github.lgpy.familycare.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.medication.PillColor
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.domain.medication.PillShape
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.theme.FamilyCareDimens

/**
 * How a dose slot reads at a glance.
 *
 * - [DONE] neutral, dashed outline, no action: the dose is history.
 * - [NEXT] primary container: the group that is due or overdue.
 * - [UPCOMING] surface container: later today.
 *
 * "Done" is deliberately neutral (onSurfaceVariant), never green.
 */
enum class DoseSlotState { DONE, NEXT, UPCOMING }

/**
 * Everything one dose slot shows.
 *
 * @param patientId Identifies the patient for the avatar colour.
 * @param patientName The short name shown next to the avatar ("Maria", "Mom").
 * @param meta The supporting line used when [pills] is empty ("2 medicines").
 * @param state Which of the three looks to use.
 * @param pills How each medicine of the dose looks, in dose order. A null
 *   entry (a medicine with no look set) draws the neutral pill glyph, so the
 *   row never has a hole in it.
 * @param givenText For [DoseSlotState.DONE]: "Given by Mom at 08:04".
 * @param actionLabel The button label ("Give"), null for no action.
 * @param onAction What the button does; required when [actionLabel] is set.
 * @param onClick What tapping the slot itself does (opens the dose-slot
 *   screen), null when the slot is not a link.
 */
@Immutable
data class DoseSlotUi(
    val patientId: PatientId,
    val patientName: String,
    val meta: String,
    val state: DoseSlotState,
    val pills: List<PillLook?> = emptyList(),
    val givenText: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * One patient's doses at one time, as a card.
 *
 * Avatar, name and the row of pill pictures on the left; the action (when there
 * is one) on the right via [ReflowRow], so at a large system font the button
 * drops below the name instead of squeezing it. Corner radius 20 dp, padding
 * 12×14 dp.
 *
 * Under the name comes a wrapping row of 22 dp [PillImage]s, one per medicine
 * of the dose, followed by "N pills" when there is more than one to count. With
 * no pill looks it falls back to [DoseSlotUi.meta], so a slot never loses its
 * count. Names and pill rows are never truncated; they wrap.
 * [DoseSlotState.DONE] draws a transparent slot with a 1 dp dashed
 * outlineVariant border and neutral text, plus a check-circle line with
 * [DoseSlotUi.givenText].
 *
 * Stateless.
 */
@Composable
fun DoseSlot(
    slot: DoseSlotUi,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val done = slot.state == DoseSlotState.DONE
    val shape = RoundedCornerShape(FamilyCareDimens.CardRadius)
    val container = when (slot.state) {
        DoseSlotState.DONE -> Color.Transparent
        DoseSlotState.NEXT -> scheme.primaryContainer
        DoseSlotState.UPCOMING -> scheme.surfaceContainer
    }
    val contentColor = when (slot.state) {
        DoseSlotState.DONE -> scheme.onSurfaceVariant
        DoseSlotState.NEXT -> scheme.onPrimaryContainer
        DoseSlotState.UPCOMING -> scheme.onSurface
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(container)
                .then(
                    if (done) {
                        Modifier.dashedOutline(scheme.outlineVariant, FamilyCareDimens.CardRadius)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (slot.onClick != null) {
                        Modifier.clickable(onClick = slot.onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(
                    horizontal = FamilyCareDimens.SlotHorizontalPadding,
                    vertical = FamilyCareDimens.SlotVerticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReflowRow(
                modifier = Modifier.fillMaxWidth(),
                spacing = 12.dp,
                content = { WhoRow(slot) },
                action = {
                    if (!done && slot.actionLabel != null && slot.onAction != null) {
                        SlotActionButton(
                            label = slot.actionLabel,
                            next = slot.state == DoseSlotState.NEXT,
                            onClick = slot.onAction,
                        )
                    }
                },
            )
            if (done && slot.givenText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = FcIcons.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = slot.givenText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhoRow(slot: DoseSlotUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PatientAvatar(
            patientId = slot.patientId,
            name = slot.patientName,
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = slot.patientName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            if (slot.pills.isEmpty()) {
                Text(
                    text = slot.meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                )
            } else {
                FlowRow(
                    modifier = Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    slot.pills.forEach { look ->
                        PillImage(
                            look = look,
                            size = 22.dp,
                            contentDescription = null,
                        )
                    }
                    if (slot.pills.size > 1) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.meds_slot_pills,
                                slot.pills.size,
                                slot.pills.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotActionButton(
    label: String,
    next: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = FamilyCareDimens.MinTouchTarget),
        shape = RoundedCornerShape(percent = 50),
        colors = if (next) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            )
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** A 1 dp dashed rounded outline, drawn inside the bounds. */
private fun Modifier.dashedOutline(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx() - stroke / 2f),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        ),
    )
}

@ScalePreviews
@Composable
private fun DoseSlotPreview() {
    val scheme = MaterialTheme.colorScheme
    val losartan = PillLook(
        shape = PillShape.ROUND,
        color = PillColor.WHITE,
        score = true,
    )
    val omeprazole = PillLook(
        shape = PillShape.CAPSULE,
        color = PillColor.PURPLE,
        color2 = PillColor.PINK,
    )
    Surface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DoseSlot(
                DoseSlotUi(
                    patientId = PatientId("antonio"),
                    patientName = "António",
                    meta = "3 medicines",
                    state = DoseSlotState.DONE,
                    pills = listOf(losartan, omeprazole, null),
                    givenText = "Given by Mom at 08:04",
                ),
            )
            DoseSlot(
                DoseSlotUi(
                    patientId = PatientId("maria"),
                    patientName = "Maria",
                    meta = "2 medicines",
                    state = DoseSlotState.NEXT,
                    pills = listOf(omeprazole, losartan),
                    actionLabel = "Give",
                    onAction = {},
                ),
            )
            DoseSlot(
                DoseSlotUi(
                    patientId = PatientId("you"),
                    patientName = "You",
                    meta = "1 medicine",
                    state = DoseSlotState.UPCOMING,
                    actionLabel = "Give",
                    onAction = {},
                ),
            )
        }
    }
}
