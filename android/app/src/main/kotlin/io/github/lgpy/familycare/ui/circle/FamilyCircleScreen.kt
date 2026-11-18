package io.github.lgpy.familycare.ui.circle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.ui.R
import java.util.Date
import kotlin.time.Instant
import org.koin.compose.viewmodel.koinViewModel

/**
 * More → Family circle: the circle's name and members, and — for the owner —
 * the invitations (docs/ux/screens.md §7).
 *
 * Members come from the local database. The pending-invitation list is an online
 * read, so it is fetched on entry and can be refreshed; every online action shows
 * its failure ("no connection") instead of failing silently.
 */
@Composable
fun FamilyCircleScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<FamilyCircleViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var email by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isOwner) {
        if (uiState.isOwner) viewModel.refreshInvitations()
    }

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
                text = stringResource(R.string.family_circle_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        if (!uiState.loaded) {
            CircularProgressIndicator()
        }

        if (uiState.circleName.isNotEmpty()) {
            Text(
                text = uiState.circleName,
                style = MaterialTheme.typography.titleLarge,
            )
        }

        uiState.actionError?.let { error ->
            ErrorText(text = stringResource(error.messageRes()))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.family_circle_members_title),
            style = MaterialTheme.typography.titleMedium,
        )
        uiState.members.forEach { member ->
            MemberRow(
                member = member,
                canRemove = uiState.isOwner && !member.isMe,
                onRemove = { viewModel.requestRemoveMember(member) },
            )
        }

        if (uiState.isOwner) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.family_circle_invite_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.family_circle_invite_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    viewModel.dismissError()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.inviteBusy,
                singleLine = true,
                label = { Text(text = stringResource(R.string.family_circle_invite_email_label)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
            )
            uiState.inviteError?.let { error ->
                ErrorText(text = stringResource(error.messageRes()))
            }
            Button(
                onClick = { viewModel.invite(email) },
                enabled = !uiState.inviteBusy,
            ) {
                Text(text = stringResource(R.string.family_circle_invite_action))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.family_circle_pending_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(
                    onClick = viewModel::refreshInvitations,
                    enabled = !uiState.invitationsLoading,
                ) {
                    Text(text = stringResource(R.string.family_circle_pending_refresh))
                }
            }

            if (uiState.invitationsLoading) {
                CircularProgressIndicator()
            }
            uiState.invitationsError?.let { error ->
                ErrorText(text = stringResource(error.messageRes()))
            }
            if (uiState.invitationsLoaded && uiState.invitations.isEmpty()) {
                Text(
                    text = stringResource(R.string.family_circle_pending_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            uiState.invitations.forEach { invitation ->
                InvitationRow(
                    invitation = invitation,
                    onRevoke = { viewModel.requestRevoke(invitation) },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    uiState.shownInvite?.let { invite ->
        val copyLabel = stringResource(R.string.family_circle_invite_copy)
        val shareTitle = stringResource(R.string.family_circle_invite_share_chooser)
        val shareMessage = stringResource(
            R.string.family_circle_invite_share_message,
            invite.email,
            invite.code,
        )

        AlertDialog(
            onDismissRequest = {
                copied = false
                viewModel.dismissInvite()
            },
            title = {
                Text(text = stringResource(R.string.family_circle_invite_dialog_title, invite.email))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.family_circle_invite_dialog_body,
                            invite.email,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = invite.code,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                copyToClipboard(context, invite.code)
                                copied = true
                            },
                        ) {
                            Text(text = copyLabel)
                        }
                        TextButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareMessage)
                                }
                                context.startActivity(Intent.createChooser(send, shareTitle))
                            },
                        ) {
                            Text(text = stringResource(R.string.family_circle_invite_share))
                        }
                    }
                    if (copied) {
                        Text(
                            text = stringResource(R.string.family_circle_invite_copied),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        copied = false
                        viewModel.dismissInvite()
                    },
                ) {
                    Text(text = stringResource(R.string.close))
                }
            },
        )
    }

    uiState.pendingRemoval?.let { member ->
        val name = member.displayName
            ?: stringResource(R.string.family_circle_member_unnamed)

        AlertDialog(
            onDismissRequest = viewModel::cancelRemoveMember,
            title = {
                Text(text = stringResource(R.string.family_circle_remove_member_title, name))
            },
            text = { Text(text = stringResource(R.string.family_circle_remove_member_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemoveMember) {
                    Text(text = stringResource(R.string.family_circle_remove_member_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRemoveMember) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    uiState.pendingRevoke?.let { invitation ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRevoke,
            title = {
                Text(
                    text = stringResource(
                        R.string.family_circle_revoke_title,
                        invitation.email,
                    ),
                )
            },
            text = { Text(text = stringResource(R.string.family_circle_revoke_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRevoke) {
                    Text(text = stringResource(R.string.family_circle_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRevoke) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MemberRow(
    member: CircleMember,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    val rawName = member.displayName
        ?: stringResource(R.string.family_circle_member_unnamed)
    val name = if (member.isMe) {
        stringResource(R.string.family_circle_member_you, rawName)
    } else {
        rawName
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(member.role.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (canRemove) {
            TextButton(onClick = onRemove) {
                Text(text = stringResource(R.string.family_circle_remove))
            }
        }
    }
}

@Composable
private fun InvitationRow(
    invitation: Invitation,
    onRevoke: () -> Unit,
) {
    val context = LocalContext.current
    val expires = invitation.expiresAt?.let { formatDate(context, it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = invitation.email, style = MaterialTheme.typography.bodyLarge)
            if (expires != null) {
                Text(
                    text = stringResource(R.string.family_circle_pending_expires, expires),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        TextButton(onClick = onRevoke) {
            Text(text = stringResource(R.string.family_circle_revoke))
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@StringRes
internal fun FamilyCircleError.messageRes(): Int = when (this) {
    FamilyCircleError.InvalidEmail -> R.string.error_invalid_email
    FamilyCircleError.InvalidCode -> R.string.error_invalid_code
    FamilyCircleError.Offline -> R.string.error_offline
    FamilyCircleError.NotAllowed -> R.string.error_not_allowed
    FamilyCircleError.LastOwner -> R.string.error_last_owner
    FamilyCircleError.AlreadyAccepted -> R.string.error_already_accepted
    FamilyCircleError.NotFound -> R.string.error_not_found
    FamilyCircleError.Unknown -> R.string.error_unknown
}

@StringRes
internal fun MemberRole.labelRes(): Int = when (this) {
    MemberRole.OWNER -> R.string.family_circle_role_owner
    MemberRole.CAREGIVER -> R.string.family_circle_role_caregiver
    MemberRole.VIEWER -> R.string.family_circle_role_viewer
}

/** Invitation expiry in the device's locale and time zone. */
private fun formatDate(context: Context, instant: Instant): String {
    val millis = instant.epochSeconds * 1_000 + instant.nanosecondsOfSecond / 1_000_000
    return DateFormat.getMediumDateFormat(context).format(Date(millis))
}

private fun copyToClipboard(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("family-care-invite-code", code))
}
