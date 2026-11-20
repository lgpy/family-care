package io.github.lgpy.familycare.feature.medications.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.PillAmount
import java.math.BigDecimal

/**
 * How much is taken at a time, set with two round buttons that step by half a
 * unit, the amount written out ("1 tablet") and the pills drawn under it.
 *
 * It replaced a text box with − and + inside it plus a row of quick-amount
 * chips: one control, read at a glance, with the common amounts (½, 1, 1½, 2)
 * a tap or two away. An unusual amount (10 ml, ¼) is typed: tapping the amount
 * opens a small dialog with a number field.
 *
 * [time] switches to the slim per-time row used while "Different amount per
 * time" is on: the time on the left, the stepper on the right.
 */
@Composable
fun DoseStepper(
    text: String,
    unit: String,
    pillLook: PillLook?,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    time: String? = null,
    isError: Boolean = false,
) {
    val amount = parseDoseQuantity(text)
    var typing by rememberSaveable { mutableStateOf(false) }
    val decreaseLabel = if (time == null) {
        stringResource(R.string.meds_decrease_amount)
    } else {
        stringResource(R.string.meds_decrease_time_amount, time)
    }
    val increaseLabel = if (time == null) {
        stringResource(R.string.meds_increase_amount)
    } else {
        stringResource(R.string.meds_increase_time_amount, time)
    }
    val unitLabel = stockUnitLabel(unit, if (amount != null && amount > BigDecimal.ONE) 2 else 1)
    val amountText = if (amount != null) "${formatQuantity(amount)} $unitLabel" else text
    val amountColor = if (isError || amount == null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val typeLabel = stringResource(R.string.meds_dose_type)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (time == null) 20.dp else 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        if (time == null) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton(FcIcons.Remove, decreaseLabel, enabled, size = 56.dp) { onStep(-1) }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = enabled, onClickLabel = typeLabel, role = Role.Button) {
                            typing = true
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = amountText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = amountColor,
                        textAlign = TextAlign.Center,
                    )
                    if (amount != null) {
                        PillAmount(look = pillLook, quantity = amount, size = 28.dp)
                    }
                }
                StepButton(FcIcons.Add, increaseLabel, enabled, size = 56.dp) { onStep(1) }
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                StepButton(FcIcons.Remove, decreaseLabel, enabled, size = 44.dp) { onStep(-1) }
                Row(
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled, onClickLabel = typeLabel, role = Role.Button) {
                            typing = true
                        }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    if (amount != null) {
                        PillAmount(look = pillLook, quantity = amount, size = 20.dp)
                    }
                    Text(
                        text = if (amount != null) formatQuantity(amount) else text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = amountColor,
                    )
                }
                StepButton(FcIcons.Add, increaseLabel, enabled, size = 44.dp) { onStep(1) }
            }
        }
    }

    if (typing) {
        TypeAmountDialog(
            initial = text,
            unitLabel = unitLabel,
            onDismiss = { typing = false },
            onConfirm = { value ->
                onValueChange(value)
                typing = false
            },
        )
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
    ) {
        Icon(imageVector = icon, contentDescription = label)
    }
}

@Composable
private fun TypeAmountDialog(
    initial: String,
    unitLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val valid = parseDoseQuantity(value) != null
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.meds_dose_type_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = value.isNotBlank() && !valid,
                suffix = { Text(text = unitLabel) },
                textStyle = MaterialTheme.typography.titleLarge,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = valid) {
                Text(text = stringResource(R.string.meds_dose_type_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
