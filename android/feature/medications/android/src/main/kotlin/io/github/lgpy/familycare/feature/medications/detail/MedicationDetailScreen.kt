package io.github.lgpy.familycare.feature.medications.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.PillTile
import io.github.lgpy.familycare.core.ui.components.StockMeter
import io.github.lgpy.familycare.core.ui.components.stockMeterFraction
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.StockSummary
import io.github.lgpy.familycare.feature.medications.ui.PrescriptionState
import io.github.lgpy.familycare.feature.medications.ui.FieldError
import io.github.lgpy.familycare.feature.medications.ui.MedsMetaItem
import io.github.lgpy.familycare.feature.medications.ui.MedsSectionTitle
import io.github.lgpy.familycare.feature.medications.ui.MedsTimeChip
import io.github.lgpy.familycare.feature.medications.ui.PillLookSheet
import io.github.lgpy.familycare.feature.medications.ui.formatQuantity
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.labelRes
import io.github.lgpy.familycare.feature.medications.ui.localizedDate
import io.github.lgpy.familycare.feature.medications.ui.localizedMonthYear
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import io.github.lgpy.familycare.feature.medications.ui.prescriptionState
import io.github.lgpy.familycare.feature.medications.ui.regimenSummary
import io.github.lgpy.familycare.feature.medications.ui.stockUnitLabel
import io.github.lgpy.familycare.feature.medications.ui.timeOfDayIcon
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One medication, in the "screens v2" shape (docs/ux/mockups/screens-v2.html,
 * `medDetail`): the pill picture with its palette badge, the name and strength,
 * the patient and form, then the icon-headed sections **When**, **Stock** and
 * **Prescription**, the earlier schedule versions and the recent doses.
 *
 * The pill picture is the way into the same look customizer the new-medication
 * form uses ([PillLookSheet]); saving it goes through the repository's update,
 * so every other field of the medication stays as it was.
 *
 * Every action the older screen had is still reachable: the top bar holds
 * "Edit details", the overflow menu holds "Change dose or schedule", "Discard
 * stock" and "Stop", and the stock card holds "Bought" and "Count" — the same
 * dialogs, with the same validation. Viewers see the same information without
 * the actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicationDetailScreen(
    medicationId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onChangeSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<MedicationDetailViewModel>(
        parameters = { parametersOf(medicationId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val stopped = uiState.medication?.medication?.isStopped == true
        DetailTopBar(
            canWrite = uiState.canWrite,
            stopped = stopped,
            canDiscard = uiState.stock != null,
            onBack = onBack,
            onEdit = onEdit,
            onChangeSchedule = onChangeSchedule,
            onDiscard = viewModel::requestDiscard,
            onStop = viewModel::requestStop,
        )

        when {
            !uiState.loaded -> CircularProgressIndicator()
            uiState.notFound || uiState.medication == null -> Text(
                text = stringResource(R.string.meds_not_found),
                style = MaterialTheme.typography.bodyLarge,
            )

            else -> {
                val item = uiState.medication!!
                val medication = item.medication
                val canAct = uiState.canWrite && !medication.isStopped

                if (medication.isStopped) {
                    medication.stoppedAt?.let { stoppedAt ->
                        Text(
                            text = stringResource(
                                R.string.meds_detail_stopped,
                                localizedDate(stoppedAt.atZone(ZoneId.systemDefault()).toLocalDate()),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (!uiState.canWrite) {
                    Text(
                        text = stringResource(R.string.patient_read_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                MedicationHero(
                    item = item,
                    canWrite = canAct,
                    onOpenLook = viewModel::requestPillLook,
                )

                WhenSection(
                    regimen = item.currentRegimen,
                    foodNote = medication.instructions,
                    canEdit = canAct && item.currentRegimen != null,
                    onChange = onChangeSchedule,
                )

                uiState.stock?.let { overview ->
                    StockSection(
                        summary = overview.summary,
                        stockUnit = medication.stockUnit,
                        canWrite = canAct,
                        onRefill = viewModel::requestRefill,
                        onCount = viewModel::requestRecount,
                    )
                }

                if (medication.requiresPrescription || medication.prescriptionValidUntil != null) {
                    PrescriptionSection(medication = medication)
                }

                if (uiState.history.isNotEmpty()) {
                    HistorySection(history = uiState.history)
                }

                LogSection(entries = uiState.recentEntries)

                uiState.error?.let { error ->
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (uiState.showPillLook) {
        PillLookSheet(
            initial = uiState.medication?.medication?.pillLook,
            onDismiss = viewModel::dismissPillLook,
            onDone = viewModel::savePillLook,
        )
    }

    if (uiState.showStop) {
        StopDialog(
            uiState = uiState,
            onDismiss = viewModel::dismissStop,
            onLastDayChanged = viewModel::onLastDayChanged,
            onConfirm = viewModel::confirmStop,
        )
    }

    uiState.stockAction?.let { action ->
        StockDialog(
            uiState = uiState,
            action = action,
            onDismiss = viewModel::dismissStock,
            onQuantityChanged = viewModel::onStockQuantityChanged,
            onReasonChanged = viewModel::onStockReasonChanged,
            onConfirm = viewModel::confirmStock,
        )
    }
}

/**
 * Back, edit and the overflow: the actions that do not deserve a section of
 * their own stay one tap away without crowding the mockup's header.
 */
@Composable
private fun DetailTopBar(
    canWrite: Boolean,
    stopped: Boolean,
    canDiscard: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onChangeSchedule: () -> Unit,
    onDiscard: () -> Unit,
    onStop: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = canWrite && (!stopped || canDiscard)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = FcIcons.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (canWrite && !stopped) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = FcIcons.Edit,
                    contentDescription = stringResource(R.string.meds_detail_edit),
                )
            }
        }
        if (hasMenu) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = FcIcons.MoreVert,
                        contentDescription = stringResource(R.string.meds_detail_actions),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!stopped) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.meds_detail_change)) },
                            leadingIcon = { Icon(imageVector = FcIcons.Schedule, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onChangeSchedule()
                            },
                        )
                    }
                    if (canDiscard) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.meds_stock_discard)) },
                            leadingIcon = { Icon(imageVector = FcIcons.Tally3, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDiscard()
                            },
                        )
                    }
                    if (!stopped) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.meds_detail_stop),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = FcIcons.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onStop()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The large pill tile with its palette badge, then the name with the strength
 * as one title, and the meta row: the patient (avatar, 18 dp) and the form.
 *
 * The badge is a 30 dp circle inside a 48 dp target, so it is comfortable to
 * tap without growing the tile. The title never truncates: a long name wraps
 * and the header grows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedicationHero(
    item: MedicationWithRegimens,
    canWrite: Boolean,
    onOpenLook: () -> Unit,
) {
    val medication = item.medication
    val lookLabel = stringResource(R.string.meds_detail_change_look)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PillTile(look = medication.pillLook, size = 84.dp, contentDescription = null)
            if (canWrite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenLook)
                        .semantics { contentDescription = lookLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = FcIcons.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = medication.strength
                    ?.let { "${medication.name} $it" }
                    ?: medication.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PatientAvatar(
                        patientId = medication.patientId,
                        name = item.patient.displayName,
                        size = 18.dp,
                    )
                    Text(
                        text = item.patient.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MedsMetaItem(
                    icon = FcIcons.Pill,
                    text = stringResource(medication.form.labelRes()),
                )
            }
        }
    }
}

/**
 * **When**: the current schedule as a summary plus one chip per dose time with
 * its time-of-day icon and amount, and the food note when the medication has
 * instructions. The whole row opens the existing change-regimen screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenSection(
    regimen: Regimen?,
    foodNote: String?,
    canEdit: Boolean,
    onChange: () -> Unit,
) {
    MedsSectionTitle(
        icon = FcIcons.Schedule,
        title = stringResource(R.string.meds_when_section),
    )
    SectionCard(onClick = if (canEdit) onChange else null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = regimen?.let { regimenSummary(it) }
                        ?: stringResource(R.string.meds_stock_no_schedule),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (regimen != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        regimen.sortedTimes().forEach { doseTime ->
                            MedsTimeChip(
                                time = doseTime.time,
                                quantity = doseTime.quantity,
                                icon = timeOfDayIcon(doseTime.time),
                            )
                        }
                    }
                }
                foodNote?.let { note ->
                    MedsMetaItem(icon = FcIcons.Restaurant, text = note)
                }
            }
            if (canEdit) {
                Icon(
                    imageVector = FcIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * **Stock**: the count and unit large, the days left in [StockMeter], and the
 * two common movements as buttons — "Bought" (one refill) and "Count" (one
 * recount). Discarding stays in the overflow, since it is rare.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockSection(
    summary: StockSummary,
    stockUnit: String,
    canWrite: Boolean,
    onRefill: () -> Unit,
    onCount: () -> Unit,
) {
    val days = summary.daysRemaining
    MedsSectionTitle(
        icon = FcIcons.Inventory2,
        title = stringResource(R.string.meds_step_stock),
    )
    SectionCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = formatQuantity(summary.displayStock),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stockUnitLabel(
                    unit = stockUnit,
                    count = summary.displayStock.max(BigDecimal.ZERO).toInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (days != null) {
            StockMeter(
                daysLeft = pluralStringResource(R.plurals.meds_stock_days_left, days, days),
                fraction = stockMeterFraction(days),
                low = summary.low,
            )
        } else {
            Text(
                text = stringResource(R.string.meds_stock_no_schedule),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (canWrite) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onRefill,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = FcIcons.AddShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.shopping_bought))
                }
                OutlinedButton(
                    onClick = onCount,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = FcIcons.Tally3,
                        contentDescription = null,
                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.meds_stock_count))
                }
            }
        }
    }
}

/**
 * **Prescription**: one consistent line for the medication's own flags — "No
 * prescription needed", "Valid until <month year>", or a call to action when a
 * required prescription is missing or expired.
 */
@Composable
private fun PrescriptionSection(medication: Medication) {
    val validUntil = medication.prescriptionValidUntil
    val state = prescriptionState(
        requiresPrescription = medication.requiresPrescription,
        prescriptionValidUntil = validUntil,
        today = LocalDate.now(),
    )
    MedsSectionTitle(
        icon = FcIcons.Prescriptions,
        title = stringResource(R.string.meds_detail_prescription_section),
    )
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = FcIcons.ReceiptLong, contentDescription = null)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = when (state) {
                        PrescriptionState.NOT_REQUIRED ->
                            stringResource(R.string.meds_detail_no_prescription)

                        PrescriptionState.NEEDED ->
                            stringResource(R.string.meds_detail_prescription_needed)

                        PrescriptionState.VALID -> stringResource(
                            R.string.meds_detail_prescription_valid_until,
                            validUntil?.let { localizedMonthYear(it) }.orEmpty(),
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (state) {
                        PrescriptionState.NEEDED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (state == PrescriptionState.NEEDED) {
                    Text(
                        text = stringResource(R.string.meds_detail_prescription_needed_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** **Earlier schedules**, oldest first; the history is kept when a version ends. */
@Composable
private fun HistorySection(history: List<Regimen>) {
    MedsSectionTitle(
        icon = FcIcons.History,
        title = stringResource(R.string.meds_detail_history),
    )
    SectionCard {
        history.forEach { regimen ->
            Text(
                text = historyLine(regimen),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The recent doses, newest first, exactly as the ledger recorded them. */
@Composable
private fun LogSection(entries: List<DoseLogEntry>) {
    MedsSectionTitle(
        icon = FcIcons.Medication,
        title = stringResource(R.string.meds_detail_log),
    )
    SectionCard {
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.meds_detail_log_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        entries.forEach { entry ->
            val whenText = localizedDate(entry.scheduledLocal.toLocalDate()) +
                stringResource(R.string.meds_list_separator) +
                formatTime(entry.scheduledLocal.toLocalTime())
            val what = stringResource(
                if (entry.outcome == DoseOutcome.TAKEN) {
                    R.string.meds_status_taken
                } else {
                    R.string.meds_status_skipped
                },
            ).let { outcomeText ->
                entry.skipReason?.let { reason ->
                    stringResource(
                        R.string.meds_log_what_with_reason,
                        outcomeText,
                        stringResource(reason.labelRes()),
                    )
                } ?: outcomeText
            }
            Text(
                text = stringResource(R.string.meds_log_line, whenText, what),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The mockup's list card: `surfaceContainerLow`, 20 dp corners, 14 dp inside.
 * With [onClick] it is the whole row's button (the When card); without, it is
 * a plain container and its content owns any interaction.
 */
@Composable
private fun SectionCard(
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    if (onClick == null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = color,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = color,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** "1 at 08:00, daily until 3 Mar 2027" / "… since 10 Mar 2027". */
@Composable
private fun historyLine(regimen: Regimen): String {
    val summary = regimenSummary(regimen)
    val end = regimen.endDate
    return if (end == null) {
        stringResource(R.string.meds_history_since, summary, localizedDate(regimen.startDate))
    } else {
        stringResource(R.string.meds_history_until, summary, localizedDate(end))
    }
}

/** Refill, recount and discard: one quantity, plus a reason for a discard. */
@Composable
private fun StockDialog(
    uiState: MedicationDetailUiState,
    action: StockAction,
    onDismiss: () -> Unit,
    onQuantityChanged: (String) -> Unit,
    onReasonChanged: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    when (action) {
                        StockAction.REFILL -> R.string.meds_stock_refill_title
                        StockAction.RECOUNT -> R.string.meds_stock_recount_title
                        StockAction.DISCARD -> R.string.meds_stock_discard_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when (action) {
                        StockAction.REFILL -> stringResource(R.string.meds_stock_refill_body)
                        StockAction.RECOUNT -> stringResource(
                            R.string.meds_stock_count_label,
                            uiState.medication?.medication?.stockUnit.orEmpty(),
                        )

                        StockAction.DISCARD -> stringResource(R.string.meds_stock_discard_body)
                    },
                )
                OutlinedTextField(
                    value = uiState.stockQuantity,
                    onValueChange = onQuantityChanged,
                    label = { Text(text = stringResource(R.string.meds_stock_quantity_label)) },
                    isError = MedicationField.STOCK_QUANTITY in uiState.stockErrors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                FieldError(
                    MedicationField.STOCK_QUANTITY in uiState.stockErrors,
                    if (action == StockAction.RECOUNT) {
                        R.string.meds_error_stock_quantity
                    } else {
                        R.string.meds_error_stock_quantity_positive
                    },
                )
                if (action == StockAction.DISCARD) {
                    OutlinedTextField(
                        value = uiState.stockReason,
                        onValueChange = onReasonChanged,
                        label = { Text(text = stringResource(R.string.meds_stock_reason_label)) },
                        isError = MedicationField.STOCK_REASON in uiState.stockErrors,
                        singleLine = true,
                    )
                    FieldError(
                        MedicationField.STOCK_REASON in uiState.stockErrors,
                        R.string.meds_error_stock_reason,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !uiState.recordingStock) {
                Text(
                    text = stringResource(
                        when (action) {
                            StockAction.REFILL -> R.string.meds_stock_refill_action
                            StockAction.RECOUNT -> R.string.meds_stock_recount_action
                            StockAction.DISCARD -> R.string.meds_stock_discard_action
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun StopDialog(
    uiState: MedicationDetailUiState,
    onDismiss: () -> Unit,
    onLastDayChanged: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.meds_stop_title,
                    uiState.medication?.medication?.name.orEmpty(),
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.meds_stop_body))
                OutlinedTextField(
                    value = uiState.stopLastDay,
                    onValueChange = onLastDayChanged,
                    label = { Text(text = stringResource(R.string.meds_stop_last_day)) },
                    supportingText = { Text(text = stringResource(R.string.meds_date_helper)) },
                    singleLine = true,
                    isError = uiState.stopDateInvalid,
                )
                if (uiState.stopDateInvalid) {
                    Text(
                        text = stringResource(R.string.meds_error_end),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !uiState.stopping) {
                Text(text = stringResource(R.string.meds_stop_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
