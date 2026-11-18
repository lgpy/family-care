package io.github.lgpy.familycare.feature.medications.shopping

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.ui.FieldError
import io.github.lgpy.familycare.feature.medications.ui.formatQuantity
import io.github.lgpy.familycare.feature.medications.ui.localizedDate
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.koin.compose.viewmodel.koinViewModel

/**
 * The shopping list (docs/ux/screens.md §5, D28): the low medications of the
 * circle, in "needs a new prescription first" and "ready to buy".
 *
 * Tapping a line opens the medication's detail; writers also get "Bought" (one
 * refill of the quantity they typed) and, on a line that needs one, "Got a new
 * prescription" (its last valid day). The list is the repository's watch, so a
 * bought or re-prescribed line leaves its group by itself.
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
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.shopping_title),
                style = MaterialTheme.typography.headlineMedium,
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
                        GroupTitle(R.string.meds_stock_needs_prescription)
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
                        HorizontalDivider()
                    }
                }
                if (uiState.readyToBuy.isNotEmpty()) {
                    item {
                        GroupTitle(R.string.meds_stock_ready_to_buy)
                    }
                    items(uiState.readyToBuy, key = { it.medicationId }) { item ->
                        ShoppingRow(
                            item = item,
                            canWrite = uiState.canWrite,
                            onOpen = { onOpenMedication(item.patientId, item.medicationId) },
                            onBought = { viewModel.requestBuy(item.medicationId) },
                            onPrescription = null,
                        )
                        HorizontalDivider()
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

/** The heading of one of the two groups. */
@Composable
private fun GroupTitle(@StringRes resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp),
    )
}

/**
 * One line: the patient, the medication, how long its stock lasts and, when it
 * applies, the prescription's state. The text block opens the detail screen; the
 * actions sit below it so a tap on the body never buys by accident.
 */
@Composable
private fun ShoppingRow(
    item: ShoppingItem,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onBought: () -> Unit,
    onPrescription: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = item.patientName, style = MaterialTheme.typography.bodySmall)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.strength?.let { "${item.medication.name} $it" }
                    ?: item.medication.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            item.daysRemaining?.let { days ->
                Text(
                    text = pluralStringResource(R.plurals.meds_stock_days_left, days, days),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(
                    R.string.meds_stock_left,
                    formatQuantity(item.displayStock),
                    item.stockUnit,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            prescriptionLine(item)?.let { line ->
                Text(text = line, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (canWrite) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBought) {
                    Text(text = stringResource(R.string.shopping_bought))
                }
                if (onPrescription != null) {
                    TextButton(onClick = onPrescription) {
                        Text(text = stringResource(R.string.shopping_got_prescription))
                    }
                }
            }
        }
    }
}

/**
 * "Prescription missing" / "Prescription expired on <date>" for a line that
 * needs one, "Prescription valid until <date>" for a line that is ready to buy
 * and has a date. Nothing when the medication needs no prescription.
 */
@Composable
private fun prescriptionLine(item: ShoppingItem): String? = when {
    item.needsPrescription && item.prescriptionMissing ->
        stringResource(R.string.shopping_prescription_missing)

    item.needsPrescription -> item.prescriptionValidUntil?.let {
        stringResource(R.string.shopping_prescription_expired, localizedDate(it))
    }

    else -> item.prescriptionValidUntil?.let {
        stringResource(R.string.meds_detail_prescription, localizedDate(it))
    }
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
