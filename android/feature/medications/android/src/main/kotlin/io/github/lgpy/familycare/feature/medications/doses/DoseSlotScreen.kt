package io.github.lgpy.familycare.feature.medications.doses

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.AllergyBanner
import io.github.lgpy.familycare.core.ui.components.DiscardChangesDialog
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.PillTile
import io.github.lgpy.familycare.core.ui.components.pillDescription
import io.github.lgpy.familycare.core.ui.patient.NoPatientAlerts
import io.github.lgpy.familycare.core.ui.patient.PatientAlerts
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.ui.formatQuantity
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.labelRes
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import io.github.lgpy.familycare.feature.medications.ui.stockUnitLabel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Give screen (docs/ux/screens.md §2, screens v2 `const giveSlot`): one
 * patient's doses at one wall-clock time.
 *
 * Top to bottom: the patient and time, their allergy banner (only when
 * non-empty, read through the core [PatientAlerts] contract so medications never
 * imports patient-info), one row per medicine with its pill picture, and a
 * checked toggle that starts checked. Unchecking a pill marks it skipped and
 * opens the existing reason list on tap. The bottom bar's one button shows what
 * saving will do; storage still gets one entry per medication through the
 * existing confirm/correct path.
 *
 * A fully recorded slot opens read-only with "Given by … at …" and a "Change"
 * action that unlocks the same rows for a correction.
 */
@Composable
fun DoseSlotScreen(
    patientId: String,
    scheduledLocal: java.time.LocalDateTime,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<DoseSlotViewModel>(
        parameters = { parametersOf(patientId, scheduledLocal.toString()) },
    )
    val uiState by viewModel.uiState.collectAsState()

    // D5: the allergies come from patient-info through the core contract. With
    // no implementation registered the screen simply shows no banner.
    val koin = getKoin()
    val alerts = remember(koin) { koin.getOrNull<PatientAlerts>() ?: NoPatientAlerts }
    val patientKey = remember(patientId) {
        patientId.takeIf { it.isNotBlank() }?.let(::PatientId)
    }
    val allergiesFlow = remember(alerts, patientKey) {
        patientKey?.let(alerts::allergies) ?: flowOf(emptyList())
    }
    val allergies by allergiesFlow.collectAsState(initial = emptyList())

    var skipTarget by remember { mutableStateOf<SlotMedicationUi?>(null) }
    var givenByOpen by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }

    val requestClose = {
        if (uiState.hasUnsavedChanges) discardDialog = true else onBack()
    }

    // The system back gesture asks the same question as the close button.
    BackHandler(enabled = uiState.hasUnsavedChanges) { discardDialog = true }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onBack()
    }

    val time = formatTime(
        uiState.items.firstOrNull()?.slot?.localTime ?: scheduledLocal.toLocalTime(),
    )

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = requestClose) {
                Icon(
                    imageVector = FcIcons.Close,
                    contentDescription = stringResource(R.string.close),
                )
            }
            Text(
                // The patient's name is on the line below, beside the avatar.
                text = stringResource(R.string.meds_slot_title, time),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        when {
            !uiState.loaded -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            uiState.notFound -> Text(
                text = stringResource(R.string.meds_slot_not_found),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )

            else -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PatientLine(
                        patientId = patientKey,
                        name = uiState.patientName.orEmpty(),
                    )

                    if (allergies.isNotEmpty()) {
                        AllergyBanner(
                            text = stringResource(
                                R.string.patient_info_allergies_banner,
                                allergies.joinToString(
                                    stringResource(R.string.patient_info_list_separator),
                                ),
                            ),
                        )
                    }

                    if (!uiState.canWrite) {
                        Text(
                            text = stringResource(R.string.patient_read_only),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    HorizontalDivider()

                    uiState.items.forEach { item ->
                        MedicationRow(
                            item = item,
                            enabled = uiState.canWrite && !uiState.confirmed,
                            givenLine = if (uiState.confirmed) {
                                givenByLine(item, uiState.members)
                            } else {
                                null
                            },
                            onToggle = {
                                // Unticking asks why at once, so the reason is
                                // never a silent default.
                                if (item.outcome == DoseOutcome.TAKEN) skipTarget = item
                                viewModel.onToggle(item.slot.regimenId.value)
                            },
                            onOpenSkipReason = { skipTarget = item },
                        )
                    }

                    if (uiState.confirmed) {
                        if (uiState.canWrite) {
                            TextButton(onClick = viewModel::onEdit) {
                                Text(text = stringResource(R.string.meds_give_edit))
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GiveChip(
                                icon = FcIcons.Person,
                                label = givenByChipLabel(uiState),
                                onClick = if (uiState.canWrite) {
                                    { givenByOpen = true }
                                } else {
                                    null
                                },
                            )
                            GiveChip(
                                icon = FcIcons.Schedule,
                                label = stringResource(R.string.meds_give_chip_at, time),
                                onClick = null,
                            )
                        }

                        OutlinedTextField(
                            value = uiState.note,
                            onValueChange = viewModel::onNoteChanged,
                            label = { Text(text = stringResource(R.string.meds_note_label)) },
                            supportingText = {
                                Text(text = stringResource(R.string.meds_note_helper))
                            },
                            enabled = uiState.canWrite,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        uiState.error?.let { error ->
                            Text(
                                text = stringResource(error.messageRes()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (!uiState.confirmed && uiState.canWrite) {
                    Surface(tonalElevation = 3.dp) {
                        Button(
                            onClick = viewModel::save,
                            enabled = !uiState.saving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(text = giveButtonLabel(uiState))
                        }
                    }
                }
            }
        }
    }

    skipTarget?.let { target ->
        val item = uiState.items.firstOrNull {
            it.slot.regimenId.value == target.slot.regimenId.value
        } ?: target
        AlertDialog(
            onDismissRequest = { skipTarget = null },
            title = { Text(text = stringResource(R.string.meds_skip_reason_label)) },
            text = {
                Column {
                    SkipReason.entries.forEach { reason ->
                        SelectableOption(
                            selected = item.skipReason == reason,
                            label = stringResource(reason.labelRes()),
                            enabled = uiState.canWrite,
                            onClick = {
                                viewModel.onSkipReason(item.slot.regimenId.value, reason)
                                skipTarget = null
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { skipTarget = null }) {
                    Text(text = stringResource(R.string.close))
                }
            },
        )
    }

    if (givenByOpen) {
        AlertDialog(
            onDismissRequest = { givenByOpen = false },
            title = { Text(text = stringResource(R.string.meds_given_by_label)) },
            text = {
                Column {
                    GivenByChoices(
                        uiState = uiState,
                        onMe = viewModel::onGivenByMe,
                        onMember = viewModel::onGivenByMember,
                        onSomeoneElse = viewModel::onGivenBySomeoneElse,
                        onSomeoneName = viewModel::onSomeoneElseName,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { givenByOpen = false }) {
                    Text(text = stringResource(R.string.close))
                }
            },
        )
    }

    if (discardDialog) {
        DiscardChangesDialog(
            onKeepEditing = { discardDialog = false },
            onDiscard = {
                discardDialog = false
                onBack()
            },
        )
    }
}

/** The 40 dp avatar, the patient's full name and the one-line helper. */
@Composable
private fun PatientLine(patientId: PatientId?, name: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        patientId?.let { PatientAvatar(patientId = it, name = name, size = 40.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.meds_give_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One medicine: its pill tile, name and strength, the dose line (amount, unit,
 * what the pill looks like, instructions) and the round check toggle.
 *
 * A skipped row dims, strikes the name through and replaces the detail line with
 * the block icon, "Skipped" and the reason chip; tapping it opens the reason
 * list. [givenLine] is only set on a confirmed slot, where the row is read-only.
 */
@Composable
private fun MedicationRow(
    item: SlotMedicationUi,
    enabled: Boolean,
    givenLine: String?,
    onToggle: () -> Unit,
    onOpenSkipReason: () -> Unit,
) {
    val skipped = item.outcome == DoseOutcome.SKIPPED
    val name = listOfNotNull(item.name.takeIf { it.isNotBlank() }, item.strength)
        .joinToString(" ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = skipped && enabled, onClick = onOpenSkipReason)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The pill's description is already in the line below, so the tile
        // itself adds nothing for TalkBack.
        PillTile(look = item.pillLook, size = 56.dp, contentDescription = null)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textDecoration = if (skipped) TextDecoration.LineThrough else null,
                color = if (skipped) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = SkippedRowAlpha)
                } else {
                    Color.Unspecified
                },
            )

            if (skipped) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = FcIcons.Block,
                        contentDescription = null,
                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.bodyMedium)),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.meds_skipped),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.skipReason?.let { reason ->
                        ReasonChip(text = stringResource(reason.labelRes()))
                    }
                }
            } else {
                Text(
                    text = doseLine(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (givenLine != null) {
                Text(
                    text = givenLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CheckToggle(
            given = !skipped,
            enabled = enabled,
            label = name,
            onCheckedChange = onToggle,
        )
    }
}

/** "1 tablet · White long tablet with a score line · Before food". */
@Composable
private fun doseLine(item: SlotMedicationUi): String {
    val separator = stringResource(R.string.meds_list_separator)
    val parts = buildList {
        val amount = formatQuantity(item.slot.quantity)
        val unit = stockUnitLabel(item.stockUnit, unitCount(item.slot.quantity))
        add(listOf(amount, unit).filter { it.isNotBlank() }.joinToString(" "))
        pillDescription(item.pillLook).takeIf { it.isNotBlank() }?.let(::add)
        item.instructions?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return parts.filter { it.isNotBlank() }.joinToString(separator)
}

/** The plural form of a unit: half a tablet still reads "tablet". */
private fun unitCount(quantity: BigDecimal): Int =
    quantity.setScale(0, RoundingMode.UP).toInt().coerceAtLeast(0)

/** The skipped row's dimming, identical in both themes. */
private const val SkippedRowAlpha = 0.55f

/** The small tonal chip beside "Skipped" with the reason. */
@Composable
private fun ReasonChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The 40 dp round check: filled primary with a check when given, an outlined
 * circle when not. The touch target stays 48 dp; [label] names the medicine for
 * TalkBack, which also announces the checked state.
 */
@Composable
private fun CheckToggle(
    given: Boolean,
    enabled: Boolean,
    label: String,
    onCheckedChange: () -> Unit,
) {
    val description = stringResource(R.string.meds_give_toggle, label)
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .toggleable(
                value = given,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onCheckedChange() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (given) MaterialTheme.colorScheme.primary else Color.Transparent,
                )
                .border(
                    width = 1.5.dp,
                    color = if (given) Color.Transparent else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FcIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (given) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** A rounded action chip: "Given by me" / "At 13:00". */
@Composable
private fun GiveChip(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The medicine and time the Give button will record. */
@Composable
private fun giveButtonLabel(uiState: DoseSlotUiState): String {
    if (uiState.saving) return stringResource(R.string.meds_step_busy)
    return when (val summary = uiState.giveSummary) {
        is GiveSummary.GiveAll -> pluralStringResource(
            R.plurals.meds_give_all,
            summary.count,
            summary.count,
        )

        is GiveSummary.GiveSome -> stringResource(
            R.string.meds_give_some,
            summary.given,
            summary.skipped,
        )

        is GiveSummary.SkipAll -> stringResource(R.string.meds_skip_all)
        null -> stringResource(R.string.meds_give)
    }
}

/** The chip's label: who the dose will be recorded as given by. */
@Composable
private fun givenByChipLabel(uiState: DoseSlotUiState): String = when (uiState.givenBy) {
    GivenByChoice.Me -> stringResource(R.string.meds_give_chip_by_me)
    GivenByChoice.Member -> uiState.members
        .firstOrNull { it.userId == uiState.memberId }
        ?.displayName
        ?.let { stringResource(R.string.meds_give_chip_by_member, it) }
        ?: stringResource(R.string.meds_give_chip_by_someone)

    GivenByChoice.SomeoneElse -> uiState.someoneName.trim()
        .takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.meds_give_chip_by_member, it) }
        ?: stringResource(R.string.meds_give_chip_by_someone)
}

/**
 * "Given by Mom at 08:04" for a recorded slot, or null when the entry does not
 * say who gave it. Mirrors the Today card's wording and never invents a giver.
 */
@Composable
private fun givenByLine(item: SlotMedicationUi, members: List<CircleMember>): String? {
    val entry = item.existing ?: return null
    val time = formatTime(entry.actualAt.atZone(ZoneId.systemDefault()).toLocalTime())
    if (entry.givenByOther) {
        return entry.givenByName
            ?.let { stringResource(R.string.meds_slot_given_by_name, it, time) }
            ?: stringResource(R.string.meds_slot_given_by_someone, time)
    }
    val userId = entry.givenByUserId ?: return stringResource(R.string.meds_slot_given_by_you, time)
    val member = members.firstOrNull { it.userId == userId }
    val displayName = member?.displayName
    return when {
        member?.isMe == true -> stringResource(R.string.meds_slot_given_by_you, time)
        displayName != null -> stringResource(R.string.meds_slot_given_by_name, displayName, time)
        else -> stringResource(R.string.meds_slot_given_by_someone, time)
    }
}

/** The "Given by" options, shared by the old screen and the Give chip's dialog. */
@Composable
private fun GivenByChoices(
    uiState: DoseSlotUiState,
    onMe: () -> Unit,
    onMember: (String) -> Unit,
    onSomeoneElse: () -> Unit,
    onSomeoneName: (String) -> Unit,
) {
    SelectableOption(
        selected = uiState.givenBy == GivenByChoice.Me,
        label = stringResource(R.string.meds_given_by_me),
        enabled = uiState.canWrite,
        onClick = onMe,
    )
    uiState.members.filterNot { it.isMe }.forEach { member ->
        SelectableOption(
            selected = uiState.givenBy == GivenByChoice.Member && uiState.memberId == member.userId,
            label = member.displayName
                ?: stringResource(R.string.family_circle_member_unnamed),
            enabled = uiState.canWrite,
            onClick = { onMember(member.userId) },
        )
    }
    SelectableOption(
        selected = uiState.givenBy == GivenByChoice.SomeoneElse,
        label = stringResource(R.string.meds_given_by_other_option),
        enabled = uiState.canWrite,
        onClick = onSomeoneElse,
    )
    if (uiState.givenBy == GivenByChoice.SomeoneElse) {
        OutlinedTextField(
            value = uiState.someoneName,
            onValueChange = onSomeoneName,
            label = { Text(text = stringResource(R.string.meds_given_by_name_label)) },
            enabled = uiState.canWrite,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A radio row without the radio's own semantics wrapper, for compact lists. */
@Composable
private fun SelectableOption(
    selected: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
