package io.github.lgpy.familycare.feature.appointments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.patient.TimelineKind
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import java.util.Locale

/*
 * The pictures of an appointment row: the kind's icon and the preparation tags.
 *
 * Shared by the Agenda, the patient's Appointments tab, the appointment detail
 * and the Today "Next 7 days" card, so a kind or a preparation phrase looks the
 * same everywhere (docs/ux/design-system.md §5–6).
 */

/**
 * The icon of an appointment type.
 *
 * [FcIcons.Cardiology] is the icon for a cardiology appointment, but this
 * version's [AppointmentKind] records no specialty: a cardiology visit arrives
 * as a consultation whose title says so, so no kind maps to it yet.
 */
@Composable
fun AppointmentKind.icon(): ImageVector = when (this) {
    AppointmentKind.BLOOD_TEST -> FcIcons.Bloodtype
    AppointmentKind.CONSULTATION -> FcIcons.Stethoscope
    AppointmentKind.EXAM -> FcIcons.Radiology
    AppointmentKind.CHECKUP -> FcIcons.Stethoscope
    AppointmentKind.OTHER -> FcIcons.Event
}

/**
 * The kind an appointment's History entry carries, so the app's History tab
 * draws the same picture as the Agenda row ([icon]).
 *
 * The mapping lives here, beside the pictures, because this module owns the
 * appointment kinds; the shell only knows the core `TimelineKind`.
 */
fun AppointmentKind.timelineKind(): TimelineKind = when (this) {
    AppointmentKind.BLOOD_TEST -> TimelineKind.APPOINTMENT_BLOOD_TEST
    AppointmentKind.CONSULTATION -> TimelineKind.APPOINTMENT_CONSULTATION
    AppointmentKind.EXAM -> TimelineKind.APPOINTMENT_EXAM
    AppointmentKind.CHECKUP -> TimelineKind.APPOINTMENT_CHECKUP
    AppointmentKind.OTHER -> TimelineKind.APPOINTMENT_OTHER
}

/** The 40 dp rounded tile with the appointment type's icon. */
@Composable
fun AppointmentKindTile(kind: AppointmentKind, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kind.icon(),
            contentDescription = null,
            tint = scheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The icon of a "Before going" chip. */
@Composable
fun PreparationTag.icon(): ImageVector = when (this) {
    PreparationTag.FASTING -> FcIcons.NoFood
    PreparationTag.RESULTS -> FcIcons.Folder
    PreparationTag.SNS_CARD -> FcIcons.Badge
    PreparationTag.MEDICINES -> FcIcons.Medication
}

/**
 * Whether a free-text preparation mentions fasting.
 *
 * The field is free text, not a flag, so the tag matches the words the app
 * itself offers in either language ("Fasting", "Jejum") anywhere in the note.
 * Lowercased with [Locale.ROOT] so a Turkish phone's dotted/dotless i cannot
 * change the match.
 */
fun preparationMentionsFasting(preparation: String): Boolean {
    val normalised = preparation.lowercase(Locale.ROOT)
    return FastingKeywords.any { keyword -> keyword in normalised }
}

private val FastingKeywords = listOf("fasting", "jejum")

/** One preparation tag drawn on a row: its words, its icon, whether it needs attention. */
data class PreparationChip(
    val text: String,
    val icon: ImageVector,
    val attention: Boolean,
)

/**
 * The tags a stored preparation shows: the chip phrases the app offers (in the
 * phone's language) plus whatever else the note says, split on its separators.
 *
 * A preparation with no chips at all is one neutral tag with the note's own
 * words, so nothing a person wrote disappears from the row.
 */
@Composable
fun preparationChips(preparation: String?): List<PreparationChip> {
    val note = preparation?.takeIf { it.isNotBlank() } ?: return emptyList()
    val parsed = parsePreparation(note)
    val chips = mutableListOf<PreparationChip>()

    parsed.tags.forEach { (tag, _) ->
        chips += PreparationChip(
            text = stringResource(tag.labelRes()),
            icon = tag.icon(),
            attention = tag == PreparationTag.FASTING,
        )
    }

    parsed.freeText
        .split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { part ->
            chips += PreparationChip(text = part, icon = freeTextIcon(part), attention = false)
        }

    return chips
}

/** The neutral tag's icon, picked from what the free text is about. */
@Composable
private fun freeTextIcon(text: String): ImageVector {
    val normalised = text.lowercase(Locale.ROOT)
    return when {
        ResultsKeywords.any { it in normalised } -> FcIcons.Folder
        SnsKeywords.any { it in normalised } -> FcIcons.Badge
        else -> FcIcons.Checklist
    }
}

private val ResultsKeywords = listOf("result", "exame", "análises", "analises", "analysis")
private val SnsKeywords = listOf("sns", "cartão", "cartao")

/** Every preparation tag of a row, wrapped instead of truncated. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreparationTags(preparation: String?, modifier: Modifier = Modifier) {
    val chips = preparationChips(preparation)
    if (chips.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { chip -> PreparationTagChip(chip) }
    }
}

/**
 * One tag: amber with bold text when it needs attention (fasting), the neutral
 * surface otherwise.
 */
@Composable
fun PreparationTagChip(chip: PreparationChip, modifier: Modifier = Modifier) {
    val colors = FamilyCareTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (chip.attention) {
            colors.attentionContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (chip.attention) {
            colors.onAttentionContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = chip.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = chip.text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
