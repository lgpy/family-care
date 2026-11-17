package io.github.lgpy.familycare.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.ui.R
import org.koin.compose.koinInject

/**
 * The signed-out gate (D12): app lock → this gate → the app shell.
 *
 * Content is not composed while the session is unknown or missing, so no
 * caregiver can reach a screen before signing in.
 */
@Composable
fun AuthGate(
    activity: Activity,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val authRepository = koinInject<AuthRepository>()
    val authState by authRepository.state.collectAsState()

    when (authState) {
        AuthState.Loading -> LoadingScreen(modifier = modifier)
        AuthState.SignedOut -> SignInScreen(activity = activity, modifier = modifier)
        is AuthState.SignedIn -> content()
    }
}

@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
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
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.sign_in_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
