package io.github.lgpy.familycare.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import org.koin.compose.viewmodel.koinViewModel

/** More → Your name: the name the other members of the circle see. */
@Composable
fun YourNameScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<YourNameViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(R.string.your_name_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        Text(
            text = stringResource(R.string.your_name_body),
            style = MaterialTheme.typography.bodyLarge,
        )

        OutlinedTextField(
            value = uiState.name,
            onValueChange = viewModel::onNameChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.loaded,
            singleLine = true,
            label = { Text(text = stringResource(R.string.your_name_label)) },
            isError = uiState.error == YourNameError.InvalidName,
            supportingText = {
                if (uiState.error == YourNameError.InvalidName) {
                    Text(
                        text = stringResource(R.string.your_name_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )

        uiState.error?.let { error ->
            if (error != YourNameError.InvalidName) {
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (uiState.saved) {
            Text(
                text = stringResource(R.string.your_name_saved),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = viewModel::save,
            enabled = uiState.loaded && !uiState.saving,
        ) {
            Text(text = stringResource(R.string.your_name_save))
        }
    }
}

@StringRes
private fun YourNameError.messageRes(): Int = when (this) {
    YourNameError.InvalidName -> R.string.your_name_error
    YourNameError.Offline -> R.string.error_offline
    YourNameError.NotSynced -> R.string.your_name_not_synced
    YourNameError.NotAllowed -> R.string.error_not_allowed
    YourNameError.Unknown -> R.string.error_unknown
}
