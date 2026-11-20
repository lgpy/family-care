package io.github.lgpy.familycare.feature.medications.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons

/**
 * A quantity that is typed, with − and + to nudge it: "60 tablets", "1 tablet".
 *
 * The amount is a real text field (`KeyboardType.Decimal`, so a phone keyboard
 * offers `,`, which [parseStockQuantity] accepts), because the previous
 * tap-only stepper made a box of 60 a sixty-tap job and a half tablet a
 * two-tap one. The buttons stay for the common small correction, and callers
 * put quick amounts ("½", "1 pack (60)") beside the field where they add one
 * tap instead of typing.
 *
 * [supportingText] is where a caller says what the number means — the add form
 * shows "About 30 days left" under the opening count — and [placeholder] keeps
 * an optional value visibly empty ("Not counted").
 *
 * The field has no visible label: the caller's section header is the label. The
 * buttons carry the only spoken context, so each caller passes its own
 * ([decreaseLabel]/[increaseLabel]) instead of a bare "Decrease".
 */
@Composable
fun QuantityField(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    unit: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    placeholder: String? = null,
    supportingText: String? = null,
    decreaseLabel: String = stringResource(R.string.meds_decrease_amount),
    increaseLabel: String = stringResource(R.string.meds_increase_amount),
) {
    val placeholderContent: (@Composable () -> Unit)? =
        if (placeholder == null) null else { { Text(text = placeholder) } }
    val suffixContent: (@Composable () -> Unit)? =
        if (unit.isBlank()) null else { { Text(text = unit) } }
    val supportingContent: (@Composable () -> Unit)? =
        if (supportingText == null) null else { { Text(text = supportingText) } }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        isError = isError,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        placeholder = placeholderContent,
        suffix = suffixContent,
        leadingIcon = {
            IconButton(onClick = { onStep(-1) }, enabled = enabled) {
                Icon(imageVector = FcIcons.Remove, contentDescription = decreaseLabel)
            }
        },
        trailingIcon = {
            IconButton(onClick = { onStep(1) }, enabled = enabled) {
                Icon(imageVector = FcIcons.Add, contentDescription = increaseLabel)
            }
        },
        supportingText = supportingContent,
        modifier = modifier.fillMaxWidth(),
    )
}
