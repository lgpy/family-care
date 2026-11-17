package io.github.lgpy.familycare.ui

import androidx.annotation.StringRes
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.locale.AppLanguage
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import org.koin.compose.koinInject

@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val languageController = koinInject<AppLanguageController>()
    var selectedLanguage by remember { mutableStateOf(languageController.current()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
        )

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
                                // The platform recreates the activity; the new
                                // locale is picked up by the next composition.
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
}

@StringRes
private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.System -> R.string.language_system
    AppLanguage.PortuguesePortugal -> R.string.language_portuguese
    AppLanguage.English -> R.string.language_english
}
