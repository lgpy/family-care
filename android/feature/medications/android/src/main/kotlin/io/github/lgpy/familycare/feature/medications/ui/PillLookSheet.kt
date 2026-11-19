package io.github.lgpy.familycare.feature.medications.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.medication.PillColor
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.domain.medication.PillShape
import io.github.lgpy.familycare.core.domain.medication.PillSize
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.PillImage
import io.github.lgpy.familycare.core.ui.components.pillColorName
import io.github.lgpy.familycare.core.ui.components.pillColorSwatch
import io.github.lgpy.familycare.core.ui.components.pillDescription

/**
 * The pill-look customizer as one bottom sheet (docs/ux/mockups/screens-v2.html,
 * "What does the pill look like?").
 *
 * It is used both by the new-medication form and by a medication's detail
 * screen, which is why it lives here instead of inside the form: the form
 * keeps the draft in its own state, the detail saves it with
 * [io.github.lgpy.familycare.feature.medications.data.MedicationRepository.update]
 * so every other field stays as it was.
 *
 * [initial] is the current look, or null when nobody has set one: the sheet
 * then starts on a white round tablet, the mockup's neutral starting point.
 * "Done" hands the draft back; dismissing leaves the medication untouched.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PillLookSheet(
    initial: PillLook?,
    onDismiss: () -> Unit,
    onDone: (PillLook) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(initial) {
        mutableStateOf(initial ?: PillLook(shape = PillShape.ROUND, color = PillColor.WHITE))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.meds_look_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            PillImage(
                look = draft,
                size = 110.dp,
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                text = pillDescription(draft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.meds_look_shape_label),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillShape.entries.forEach { shape ->
                    ShapeTile(
                        shape = shape,
                        selected = draft.shape == shape,
                        onClick = { draft = draft.withShape(shape) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.meds_look_colour_label),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillColor.entries.forEach { colour ->
                    ColourSwatch(
                        colour = colour,
                        selected = draft.color == colour,
                        onClick = { draft = draft.copy(color = colour) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(imageVector = FcIcons.Contrast, contentDescription = null)
                Text(
                    text = stringResource(R.string.meds_look_two_colours),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = draft.color2 != null,
                    onCheckedChange = { on ->
                        draft = draft.copy(
                            color2 = when {
                                !on -> null
                                draft.color2 != null -> draft.color2
                                else -> PillColor.WHITE
                            },
                        )
                    },
                    enabled = draft.shape == PillShape.CAPSULE,
                )
            }

            if (draft.shape == PillShape.CAPSULE && draft.color2 != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillColor.entries.forEach { colour ->
                        ColourSwatch(
                            colour = colour,
                            selected = draft.color2 == colour,
                            onClick = { draft = draft.copy(color2 = colour) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(imageVector = FcIcons.HorizontalRule, contentDescription = null)
                Text(
                    text = stringResource(R.string.meds_look_score),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = draft.score,
                    onCheckedChange = { draft = draft.copy(score = it) },
                    enabled = draft.shape != PillShape.CAPSULE && draft.shape != PillShape.DROPS,
                )
            }

            Text(
                text = stringResource(R.string.meds_look_size_label),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillSize.entries.forEach { size ->
                    FilterChip(
                        selected = draft.size == size,
                        onClick = { draft = draft.copy(size = size) },
                        label = { Text(text = stringResource(size.labelRes())) },
                    )
                }
            }

            Button(
                onClick = { onDone(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(imageVector = FcIcons.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.meds_look_done))
            }
        }
    }
}

/** The shape chosen; a second colour and a score line only survive where they mean something. */
private fun PillLook.withShape(shape: PillShape): PillLook = copy(
    shape = shape,
    color2 = if (shape == PillShape.CAPSULE) color2 ?: PillColor.WHITE else null,
    score = if (shape == PillShape.CAPSULE || shape == PillShape.DROPS) false else score,
)

@Composable
private fun ShapeTile(shape: PillShape, selected: Boolean, onClick: () -> Unit) {
    val description = pillDescription(PillLook(shape = shape, color = PillColor.WHITE))
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier
            .size(60.dp)
            .semantics {
                contentDescription = description
                this.selected = selected
                role = Role.RadioButton
            },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PillImage(
                look = PillLook(shape = shape, color = PillColor.WHITE),
                size = 40.dp,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun ColourSwatch(colour: PillColor, selected: Boolean, onClick: () -> Unit) {
    val name = pillColorName(colour)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(pillColorSwatch(colour))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = CircleShape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
    )
}

@Composable
private fun PillSize.labelRes(): Int = when (this) {
    PillSize.SMALL -> R.string.meds_look_size_small
    PillSize.MEDIUM -> R.string.meds_look_size_medium
    PillSize.LARGE -> R.string.meds_look_size_large
}
