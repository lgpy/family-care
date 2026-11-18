package io.github.lgpy.familycare.ui

import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.locale.AppLanguage
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * More: the family circle, the user's own name, account, language, app lock and
 * About (docs/ux/screens.md §7).
 */
@Composable
fun MoreScreen(
    onOpenFamilyCircle: () -> Unit,
    onOpenYourName: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val languageController = koinInject<AppLanguageController>()
    val viewModel = koinViewModel<MoreViewModel>()
    var selectedLanguage by remember { mutableStateOf(languageController.current()) }

    // What "System default" means here, resolved only when the user picks it.
    val deviceLanguageTags = remember {
        val locales = LocaleList.getDefault()
        List(locales.size()) { index -> locales[index].toLanguageTag() }
    }

    val authRepository = koinInject<AuthRepository>()
    val authState by authRepository.state.collectAsState()
    var confirmingSignOut by remember { mutableStateOf(false) }
    var signOutFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.more_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        // M2: the care circle's people and invitations, and the user's own name.
        MoreEntry(
            title = R.string.more_family_circle,
            body = R.string.more_family_circle_body,
            onClick = onOpenFamilyCircle,
        )
        MoreEntry(
            title = R.string.more_your_name,
            body = R.string.more_your_name_body,
            onClick = onOpenYourName,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // D12: which Google account the session belongs to, and the way out.
        Text(
            text = stringResource(R.string.settings_account_title),
            style = MaterialTheme.typography.titleMedium,
        )
        when (val state = authState) {
            is AuthState.SignedIn -> {
                Text(
                    text = stringResource(
                        R.string.settings_account_signed_in_as,
                        state.email ?: state.userId,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = {
                        signOutFailed = false
                        confirmingSignOut = true
                    },
                ) {
                    Text(text = stringResource(R.string.settings_sign_out))
                }
                if (signOutFailed) {
                    Text(
                        text = stringResource(R.string.settings_sign_out_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            else -> Text(
                text = stringResource(R.string.settings_account_signed_out),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_language_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        AppLanguage.entries.forEach { language ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedLanguage == language,
                        role = Role.RadioButton,
                        onClick = {
                            if (selectedLanguage != language) {
                                selectedLanguage = language
                                // D8: the account's language follows so the
                                // server writes notifications in it; the device
                                // half changes below and recreates the activity.
                                viewModel.onLanguageChosen(language, deviceLanguageTags)
                                languageController.set(language)
                            }
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedLanguage == language,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(language.labelRes()),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.settings_app_lock_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_app_lock_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        TextButton(onClick = onOpenAbout) {
            Text(text = stringResource(R.string.about_open))
        }
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(text = stringResource(R.string.sign_out_confirm_title)) },
            text = { Text(text = stringResource(R.string.sign_out_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingSignOut = false
                        scope.launch {
                            signOutFailed = authRepository.signOut().isFailure
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.sign_out_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MoreEntry(
    @StringRes title: Int,
    @StringRes body: Int,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@StringRes
private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.System -> R.string.language_system
    AppLanguage.PortuguesePortugal -> R.string.language_portuguese
    AppLanguage.English -> R.string.language_english
}
