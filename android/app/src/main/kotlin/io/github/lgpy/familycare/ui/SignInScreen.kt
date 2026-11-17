package io.github.lgpy.familycare.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.auth.GoogleSignIn
import io.github.lgpy.familycare.auth.GoogleSignInOutcome
import io.github.lgpy.familycare.core.ui.R
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Signed-out screen (D12): app name, what signing in is for, and one button.
 *
 * The Credential Manager sheet is requested here, from the Activity the app
 * lock already owns; the Supabase exchange happens in [SignInViewModel].
 */
@Composable
fun SignInScreen(
    activity: Activity,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<SignInViewModel>()
    val googleSignIn = koinInject<GoogleSignIn>()
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.sign_in_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.signingIn) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.sign_in_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            } else {
                Button(
                    onClick = {
                        viewModel.onSignInStarted()
                        scope.launch {
                            when (val outcome = googleSignIn.requestIdToken(activity)) {
                                is GoogleSignInOutcome.IdToken ->
                                    viewModel.onIdTokenReceived(outcome.idToken, outcome.rawNonce)

                                // Dismissing the sheet is a normal choice, not an error.
                                GoogleSignInOutcome.Cancelled -> viewModel.onCredentialCancelled()

                                GoogleSignInOutcome.Failed -> viewModel.onCredentialFailed()
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.sign_in_with_google))
                }
            }

            if (uiState.failed) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.sign_in_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
