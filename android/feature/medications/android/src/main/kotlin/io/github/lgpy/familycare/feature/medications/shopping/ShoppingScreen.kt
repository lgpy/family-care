package io.github.lgpy.familycare.feature.medications.shopping

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.PillTile
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.ui.FieldError
import io.github.lgpy.familycare.feature.medications.ui.MedsMetaItem
import io.github.lgpy.familycare.feature.medications.ui.MedsSectionTitle
import io.github.lgpy.familycare.feature.medications.ui.localizedDate
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.koin.compose.viewmodel.koinViewModel

/**
 * The shopping list in the "screens v2" shape (docs/ux/mockups/screens-v2.html,
 * `shopping`): two icon-headed groups, "Ask for a prescription first" and
 * "Ready to buy". Every row has the medicine's pill picture, its name and
 * strength, the patient (18 dp avatar) and the days left — amber with the error
 * icon, since a line only appears here when it is low — and its prescription
 * state.
 *
 * Writers get one tonal action per line ("Got prescription" on the first group,
 * "Bought" on the second) plus the secondary "Bought" on a line that still
 * needs its prescription. Both keep the existing dialogs (a quantity for the
 * refill, a date for the prescription) and the repository's own checks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    onOpenMedication: (patientId: String, medicationId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<ShoppingViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.shopping_title),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        when {
            !uiState.loaded -> item { CircularProgressIndicator() }

            uiState.isEmpty -> {
                item {
                    Text(
                        text = stringResource(R.string.shopping_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.shopping_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            else -> {
                if (uiState.needsPrescription.isNotEmpty()) {
                    item {
                        GroupTitle(
                            icon = FcIcons.Prescriptions,
                            titleRes = R.string.shopping_group_needs_prescription,
                        )
                    }
                    items(uiState.needsPrescription, key = { it.medicationId }) { item ->
                        ShoppingRow(
                            item = item,
                            canWrite = uiState.canWrite,
                            onOpen = { onOpenMedication(item.patientId, item.medicationId) },
                            onBought = { viewModel.requestBuy(item.medicationId) },
                            onPrescription = {
                                viewModel.requestPrescription(item.medicationId)
                            },
                        )
                    }
                }
                if (uiState.readyToBuy.isNotEmpty()) {
                    item {
                        GroupTitle(
                            icon = FcIcons.ShoppingCart,
                            titleRes = R.string.meds_stock_ready_to_buy,
                        )
                    }
                    items(uiState.readyToBuy, key = { it.medicationId }) { item ->
                        ShoppingRow(
                            item = item,
                            canWrite = uiState.canWrite,
                            onOpen = { onOpenMedication(item.patientId, item.medicationId) },
                            onBought = { viewModel.requestBuy(item.medicationId) },
                            onPrescription = null,
                        )
                    }
                }
            }
        }

        uiState.error?.let { error ->
            item {
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    uiState.buy?.let { item ->
        BuyDialog(
            uiState = uiState,
            item = item,
            onQuantityChanged = viewModel::onBuyQuantityChanged,
            onDismiss = viewModel::dismissBuy,
            onConfirm = viewModel::confirmBuy,
        )
    }

    uiState.prescription?.let { item ->
        key(item.medicationId) {
            PrescriptionDialog(
                saving = uiState.savingPrescription,
                onDismiss = viewModel::dismissPrescription,
                onConfirm = viewModel::confirmPrescription,
            )
        }
    }
}

/** The heading of one of the two groups: icon beside the label, never icon-only. */
@Composable
private fun GroupTitle(icon: ImageVector, @StringRes titleRes: Int) {
    MedsSectionTitle(
        icon = icon,
        title = stringResource(titleRes),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * One line: the pill picture, the medicine, the patient's avatar with the days
 * left, and the prescription state. The text block opens the detail screen;
 * the actions sit below it so a tap on the body never buys by accident.
 *
 * The first group leads with "Got prescription" and keeps "Bought" as its
 * secondary action; the second leads with "Bought".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoppingRow(
    item: ShoppingItem,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onBought: () -> Unit,
    onPrescription: (() -> Unit)?,
) {
    val lowColour = FamilyCareTheme.colors.attentionAccent

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PillTile(
                look = item.medication.pillLook,
                size = 40.dp,
                contentDescription = null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpen)
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.strength?.let { "${item.medication.name} $it" }
                            ?: item.medication.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
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
                                patientId = PatientId(item.patientId),
                                name = item.patientName,
                                size = 18.dp,
                            )
                            Text(
                                text = item.patientName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        item.daysRemaining?.let { days ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = FcIcons.ErrorFilled,
                                    contentDescription = null,
                                    tint = lowColour,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.meds_stock_days_left,
                                        days,
                                        days,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = lowColour,
                                )
                            }
                        }
                    }

                    MedsMetaItem(
                        icon = FcIcons.ReceiptLong,
                        text = prescriptionLine(item),
                    )
                }

                if (canWrite) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (onPrescription != null) {
                            FilledTonalButton(
                                onClick = onPrescription,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(
                                    imageVector = FcIcons.ReceiptLong,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.shopping_got_prescription))
                            }
                            TextButton(
                                onClick = onBought,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(
                                    imageVector = FcIcons.AddShoppingCart,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.shopping_bought))
                            }
                        } else {
                            FilledTonalButton(
                                onClick = onBought,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(
                                    imageVector = FcIcons.AddShoppingCart,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.shopping_bought))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Prescription missing" / "Prescription expired on <date>" for a line that
 * needs one, "Prescription valid until <date>" for a line that is ready to buy
 * and has a date, and "No prescription needed" when it has none.
 */
@Composable
private fun prescriptionLine(item: ShoppingItem): String = when {
    item.needsPrescription && item.prescriptionMissing ->
        stringResource(R.string.shopping_prescription_missing)

    item.needsPrescription -> item.prescriptionValidUntil?.let {
        stringResource(R.string.shopping_prescription_expired, localizedDate(it))
    } ?: stringResource(R.string.shopping_prescription_missing)

    item.prescriptionValidUntil != null -> stringResource(
        R.string.meds_detail_prescription,
        localizedDate(item.prescriptionValidUntil!!),
    )

    else -> stringResource(R.string.meds_detail_no_prescription)
}

/** "How many tablet did you buy?", one quantity — the same refill as the detail. */
@Composable
private fun BuyDialog(
    uiState: ShoppingUiState,
    item: ShoppingItem,
    onQuantityChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.shopping_buy_title, item.stockUnit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = uiState.buyQuantity,
                    onValueChange = onQuantityChanged,
                    label = { Text(text = stringResource(R.string.meds_stock_quantity_label)) },
                    isError = MedicationField.STOCK_QUANTITY in uiState.buyErrors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                FieldError(
                    MedicationField.STOCK_QUANTITY in uiState.buyErrors,
                    R.string.meds_error_stock_quantity_positive,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !uiState.recording) {
                Text(text = stringResource(R.string.meds_stock_refill_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

/** The new prescription's last valid day, as a Material date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrescriptionDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val selected = pickerState.selectedDateMillis
            TextButton(
                onClick = {
                    if (selected != null) {
                        onConfirm(
                            Instant.ofEpochMilli(selected)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate(),
                        )
                    }
                },
                enabled = selected != null && !saving,
            ) { Text(text = stringResource(R.string.shopping_prescription_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.shopping_prescription_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.shopping_prescription_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            DatePicker(state = pickerState)
        }
    }
}
