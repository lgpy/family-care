package io.github.lgpy.familycare.core.ui.lock

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.lgpy.familycare.core.ui.R

/**
 * Mandatory app lock gate (D12).
 *
 * While locked, [content] is not composed at all, so no app content can be
 * seen (or captured) behind the lock screen. The biometric/device-credential
 * prompt is shown automatically when the lock screen appears; the Unlock
 * button re-shows it after a failed or cancelled attempt.
 */
@Composable
fun AppLockGate(
    controller: AppLockController,
    activity: FragmentActivity,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val locked by controller.locked.collectAsState()
    if (!locked) {
        content()
        return
    }

    val screenLockConfigured by controller.screenLockConfigured.collectAsState()
    var authenticationFailed by remember { mutableStateOf(false) }

    val prompt = remember(activity, controller) {
        BiometricUnlockPrompt(
            activity = activity,
            onSucceeded = {
                authenticationFailed = false
                controller.unlock()
            },
            onFailed = {
                authenticationFailed = true
                controller.onAuthenticationFailed()
            },
        )
    }

    LockScreen(
        screenLockConfigured = screenLockConfigured,
        authenticationFailed = authenticationFailed,
        onUnlock = prompt::show,
        onOpenSecuritySettings = activity::openScreenLockSettings,
        modifier = modifier,
    )

    // Cold start and re-lock both land here; ask for authentication straight away.
    if (screenLockConfigured) {
        LaunchedEffect(prompt) { prompt.show() }
    }
}

@Composable
private fun LockScreen(
    screenLockConfigured: Boolean,
    authenticationFailed: Boolean,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
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
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (screenLockConfigured) {
                Text(
                    text = stringResource(R.string.lock_body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                if (authenticationFailed) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.lock_auth_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onUnlock) {
                    Text(text = stringResource(R.string.lock_unlock))
                }
            } else {
                // No PIN/pattern/password on the phone: the app stays blocked
                // (no bypass) and points at the system security settings.
                Text(
                    text = stringResource(R.string.lock_no_screen_lock_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.lock_no_screen_lock_body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onOpenSecuritySettings) {
                    Text(text = stringResource(R.string.lock_open_security_settings))
                }
            }
        }
    }
}

private class BiometricUnlockPrompt(
    private val activity: FragmentActivity,
    private val onSucceeded: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private var showing = false

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                showing = false
                onSucceeded()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                showing = false
                onFailed()
            }

            // A read failure (finger not recognised) is non-terminal: the
            // system prompt stays open and the app stays locked.
            override fun onAuthenticationFailed() = Unit
        },
    )

    fun show() {
        if (showing) return
        showing = true
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_prompt_title))
                .setSubtitle(activity.getString(R.string.lock_prompt_subtitle))
                .setAllowedAuthenticators(AppLockController.ALLOWED_AUTHENTICATORS)
                .build(),
        )
    }
}

private fun FragmentActivity.openScreenLockSettings() {
    // DEVICE_CREDENTIAL cannot be requested from the biometric enrolment
    // screen; the security-settings fallback covers PIN/pattern/password.
    val enroll = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
        putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        )
    }
    val securitySettings = Intent(Settings.ACTION_SECURITY_SETTINGS)

    for (intent in listOf(enroll, securitySettings)) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next screen.
        }
    }
}
