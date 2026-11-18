package io.github.lgpy.familycare.ui.circle

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import org.koin.compose.viewmodel.koinViewModel

/**
 * The M2 onwards gate: after the app lock and the Supabase session, the user
 * still has to belong to a care circle before any screen shows health data.
 *
 * [CircleSetupState.NotSyncedYet] is a loading screen, never the empty one, so a
 * returning user is not offered "create a circle" while their rows download.
 * When a membership is removed, [CircleGateViewModel.uiState] returns to
 * [CircleGateUiState.NoCircle] and this gate replaces the app shell by itself.
 */
@Composable
fun CircleGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val viewModel = koinViewModel<CircleGateViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        is CircleGateUiState.HasCircle -> content()
        is CircleGateUiState.Loading -> CircleGateLoading(offline = state.offline, modifier = modifier)
        CircleGateUiState.NoCircle -> CircleGateNoCircle(viewModel = viewModel, modifier = modifier)
    }
}

@Composable
private fun CircleGateLoading(
    offline: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.circle_gate_loading_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (offline) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.circle_gate_loading_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CircleGateNoCircle(
    viewModel: CircleGateViewModel,
    modifier: Modifier = Modifier,
) {
    val actionState by viewModel.actionState.collectAsState()

    var circleName by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.circle_gate_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.circle_gate_body),
                style = MaterialTheme.typography.bodyLarge,
            )

            actionState.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                // An invite for another address is fixed by using that account.
                if (error == CircleGateError.EmailMismatch) {
                    TextButton(onClick = viewModel::signOut) {
                        Text(text = stringResource(R.string.circle_gate_sign_out))
                    }
                }
            }

            if (actionState.busy) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.circle_gate_busy),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.circle_gate_create_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = circleName,
                onValueChange = {
                    circleName = it
                    viewModel.dismissError()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !actionState.busy,
                singleLine = true,
                label = { Text(text = stringResource(R.string.circle_gate_create_name_label)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
            )
            Button(
                onClick = { viewModel.createCircle(circleName) },
                enabled = !actionState.busy,
            ) {
                Text(text = stringResource(R.string.circle_gate_create_action))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.circle_gate_join_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = inviteCode,
                onValueChange = {
                    inviteCode = it
                    viewModel.dismissError()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !actionState.busy,
                singleLine = true,
                label = { Text(text = stringResource(R.string.circle_gate_join_code_label)) },
                supportingText = { Text(text = stringResource(R.string.circle_gate_join_helper)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
            )
            Button(
                onClick = { viewModel.joinWithCode(inviteCode) },
                enabled = !actionState.busy,
            ) {
                Text(text = stringResource(R.string.circle_gate_join_action))
            }
        }
    }
}

@StringRes
internal fun CircleGateError.messageRes(): Int = when (this) {
    CircleGateError.InvalidCode -> R.string.error_invalid_code
    CircleGateError.EmailMismatch -> R.string.error_email_mismatch
    CircleGateError.EmailNotConfirmed -> R.string.error_email_not_confirmed
    CircleGateError.InvalidName -> R.string.error_invalid_name
    CircleGateError.Offline -> R.string.error_offline
    CircleGateError.NotAllowed -> R.string.error_not_allowed
    CircleGateError.Unknown -> R.string.error_unknown
}
