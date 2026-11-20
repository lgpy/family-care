package io.github.lgpy.familycare.ui

import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.BuildConfig
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.canManage
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import io.github.lgpy.familycare.core.ui.locale.AppLanguage
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * More (screens v2, docs/ux/screens.md §7): the family circle, reminders, app
 * settings and data, in the mockup's four groups.
 *
 * Every entry that existed before is still reachable: the circle and invitation
 * live under Family, reminders — the phone's reliability and the per-patient
 * "who gets reminded" — under Reminders, the user's name, language, About and
 * the account under App, and export and the owner-only activity feed under Data.
 * The reminders row's status is contributed by the medications feature through a
 * core contract, so this screen names no feature module (D5).
 */
@Composable
fun MoreScreen(
    onOpenFamilyCircle: () -> Unit,
    onInviteSomeone: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenWhoReminded: () -> Unit,
    onOpenYourName: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPatients: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val languageController = koinInject<AppLanguageController>()
    val viewModel = koinViewModel<MoreViewModel>()
    var selectedLanguage by remember { mutableStateOf(languageController.current()) }
    var languageDialogOpen by remember { mutableStateOf(false) }
    var accountDialogOpen by remember { mutableStateOf(false) }

    // D30: the activity feed is owner-only, so its entry is too.
    val careCircleRepository = koinInject<CareCircleRepository>()
    val myRole by careCircleRepository.myRole.collectAsState(initial = null)

    // What "System default" means here, resolved only when the user picks it.
    val deviceLanguageTags = remember {
        val locales = LocaleList.getDefault()
        List(locales.size()) { index -> locales[index].toLanguageTag() }
    }

    val authRepository = koinInject<AuthRepository>()
    val authState by authRepository.state.collectAsState()
    var confirmingSignOut by remember { mutableStateOf(false) }
    var confirmingSignOutAll by remember { mutableStateOf(false) }
    var signOutFailed by remember { mutableStateOf(false) }
    var signOutAllFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val reminderStatus by viewModel.reminderStatus.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshReminderStatus() }

    val reminderText = when {
        reminderStatus == null -> stringResource(R.string.more_reminders_body)
        reminderStatus?.problems == 0 -> stringResource(R.string.more_reminders_all_set)
        else -> {
            val count = reminderStatus?.problems ?: 0
            pluralStringResource(R.plurals.more_reminders_problems, count, count)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(title = stringResource(R.string.more_title))

        MoreSection(title = stringResource(R.string.more_group_family)) {
            MoreRow(
                icon = FcIcons.Group,
                title = stringResource(R.string.more_circle_members),
                status = stringResource(R.string.more_family_circle_body),
                onClick = onOpenFamilyCircle,
            )
            MoreRow(
                icon = FcIcons.PersonAdd,
                title = stringResource(R.string.more_invite),
                status = stringResource(R.string.more_invite_status),
                onClick = onInviteSomeone,
            )
        }

        MoreSection(title = stringResource(R.string.more_group_reminders)) {
            MoreRow(
                icon = FcIcons.NotificationsActive,
                title = stringResource(R.string.more_reminders_reliable),
                status = reminderText,
                attention = reminderStatus?.needsAttention == true,
                onClick = onOpenReminders,
            )
            MoreRow(
                icon = FcIcons.Alarm,
                title = stringResource(R.string.more_who_reminded),
                status = stringResource(R.string.more_who_reminded_status),
                onClick = onOpenWhoReminded,
            )
        }

        MoreSection(title = stringResource(R.string.more_group_app)) {
            MoreRow(
                icon = FcIcons.Person,
                title = stringResource(R.string.more_your_name),
                status = stringResource(R.string.more_your_name_body),
                onClick = onOpenYourName,
            )
            MoreRow(
                icon = FcIcons.Language,
                title = stringResource(R.string.settings_language_title),
                status = stringResource(selectedLanguage.labelRes()),
                onClick = { languageDialogOpen = true },
            )
            MoreRow(
                icon = FcIcons.Info,
                title = stringResource(R.string.about_title),
                status = stringResource(R.string.more_about_status, BuildConfig.VERSION_NAME),
                onClick = onOpenAbout,
            )
            MoreRow(
                icon = FcIcons.Badge,
                title = stringResource(R.string.settings_account_title),
                status = when (val state = authState) {
                    is AuthState.SignedIn -> stringResource(
                        R.string.settings_account_signed_in_as,
                        state.email ?: state.userId,
                    )

                    else -> stringResource(R.string.settings_account_signed_out)
                },
                onClick = { accountDialogOpen = true },
            )
        }

        MoreSection(title = stringResource(R.string.more_group_data)) {
            MoreRow(
                icon = FcIcons.IosShare,
                title = stringResource(R.string.more_export_patient),
                status = stringResource(R.string.more_export_patient_status),
                onClick = onOpenPatients,
            )
            // D30: owners see who changed what in the circle.
            if (myRole.canManage) {
                MoreRow(
                    icon = FcIcons.ManageHistory,
                    title = stringResource(R.string.more_activity),
                    status = stringResource(R.string.more_activity_body),
                    onClick = onOpenActivity,
                )
            }
        }
    }

    if (languageDialogOpen) {
        AlertDialog(
            onDismissRequest = { languageDialogOpen = false },
            title = { Text(text = stringResource(R.string.settings_language_title)) },
            text = {
                Column {
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
                                            viewModel.onLanguageChosen(
                                                language,
                                                deviceLanguageTags,
                                            )
                                            languageController.set(language)
                                        }
                                        languageDialogOpen = false
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
                }
            },
            confirmButton = {
                TextButton(onClick = { languageDialogOpen = false }) {
                    Text(text = stringResource(R.string.close))
                }
            },
        )
    }

    if (accountDialogOpen) {
        AlertDialog(
            onDismissRequest = { accountDialogOpen = false },
            title = { Text(text = stringResource(R.string.settings_account_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = when (val state = authState) {
                            is AuthState.SignedIn -> stringResource(
                                R.string.settings_account_signed_in_as,
                                state.email ?: state.userId,
                            )

                            else -> stringResource(R.string.settings_account_signed_out)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (signOutAllFailed) {
                        Text(
                            text = stringResource(R.string.settings_sign_out_all_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (signOutFailed) {
                        Text(
                            text = stringResource(R.string.settings_sign_out_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        onClick = {
                            accountDialogOpen = false
                            signOutFailed = false
                            confirmingSignOut = true
                        },
                    ) {
                        Text(text = stringResource(R.string.settings_sign_out))
                    }
                    // D30: revokes every refresh token, so the other phones go too.
                    TextButton(
                        onClick = {
                            accountDialogOpen = false
                            signOutAllFailed = false
                            confirmingSignOutAll = true
                        },
                    ) {
                        Text(text = stringResource(R.string.settings_sign_out_all))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { accountDialogOpen = false }) {
                    Text(text = stringResource(R.string.close))
                }
            },
        )
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
    if (confirmingSignOutAll) {
        AlertDialog(
            onDismissRequest = { confirmingSignOutAll = false },
            title = { Text(text = stringResource(R.string.sign_out_all_confirm_title)) },
            text = { Text(text = stringResource(R.string.sign_out_all_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingSignOutAll = false
                        scope.launch {
                            signOutAllFailed = authRepository.signOutEverywhere().isFailure
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.sign_out_all_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOutAll = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** One group of rows under a small onSurfaceVariant header (mockup `More`). */
@Composable
private fun MoreSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * One More row: a 40 dp tile (primary container, or attention colours while
 * something needs fixing), the title, a one-line status and a chevron.
 */
@Composable
private fun MoreRow(
    icon: ImageVector,
    title: String,
    status: String,
    onClick: () -> Unit,
    attention: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = FamilyCareTheme.colors
    val tileColor = if (attention) colors.attentionContainer else scheme.primaryContainer
    val iconColor = if (attention) colors.attentionAccent else scheme.onPrimaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tileColor),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = FcIcons.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
    }
}

@StringRes
private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.System -> R.string.language_system
    AppLanguage.PortuguesePortugal -> R.string.language_portuguese
    AppLanguage.English -> R.string.language_english
}
