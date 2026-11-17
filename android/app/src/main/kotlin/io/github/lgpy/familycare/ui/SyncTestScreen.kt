package io.github.lgpy.familycare.ui

// M1 probe: the throwaway "Sync test" screen used to run M1's done-test (a note
// added in airplane mode appears on the other phone after reconnecting).
// Delete it in M2 together with core.sync_probe and SyncProbeRepository.

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.sync.ProbeNote
import io.github.lgpy.familycare.core.domain.sync.SyncProbeRepository
import io.github.lgpy.familycare.core.ui.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.compose.viewmodel.koinViewModel

/**
 * M1 done-test screen (see the file comment): the sync status, a field to add a
 * note to the probe table, and the notes themselves, each deletable.
 *
 * The status card is the only place sync errors surface; a note written here is
 * stored locally first, so this screen behaves the same offline (D3).
 */
@Composable
fun SyncTestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SyncTestViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.sync_test_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        item {
            Text(
                text = stringResource(R.string.sync_test_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { SyncStatusCard(state = state) }
        item {
            AddNoteField(
                draft = state.draft,
                canAdd = state.canAdd,
                onDraftChange = viewModel::onDraftChange,
                onAdd = viewModel::onAdd,
            )
        }

        if (state.notes.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.sync_test_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(state.notes, key = { it.id }) { note ->
                NoteRow(note = note, onDelete = { viewModel.onDelete(note.id) })
            }
        }

        item {
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.back))
            }
        }
    }
}

@Composable
private fun SyncStatusCard(
    state: SyncTestUiState,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(state.status.labelRes()),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.uploading) {
                Text(
                    text = stringResource(R.string.sync_test_uploading),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.downloading) {
                Text(
                    text = stringResource(R.string.sync_test_downloading),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = syncedAtText(state),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.hasError) {
                Text(
                    text = stringResource(R.string.sync_test_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** "Synced at <local time>", or "Not synced yet" until the first download. */
@Composable
private fun syncedAtText(state: SyncTestUiState): String {
    val lastSyncedAt = state.lastSyncedAt
    return if (state.hasSynced && lastSyncedAt != null) {
        stringResource(R.string.sync_test_synced_at, formatLocalTime(lastSyncedAt))
    } else {
        stringResource(R.string.sync_test_not_synced)
    }
}

@Composable
private fun AddNoteField(
    draft: String,
    canAdd: Boolean,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tooLong = draft.trim().length > SyncProbeRepository.MAX_TEXT_LENGTH

    Column(modifier = modifier.fillMaxWidth()) {
        // singleLine: a probe note is short and the database allows 500 chars.
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(R.string.sync_test_note_hint)) },
            singleLine = true,
            isError = tooLong,
            supportingText = if (tooLong) {
                {
                    Text(
                        text = stringResource(
                            R.string.sync_test_max_length,
                            SyncProbeRepository.MAX_TEXT_LENGTH,
                        ),
                    )
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (canAdd) onAdd() },
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onAdd, enabled = canAdd) {
                Text(text = stringResource(R.string.sync_test_add))
            }
        }
    }
}

@Composable
private fun NoteRow(
    note: ProbeNote,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = note.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                // The row is stored as ISO-8601 UTC; a reader wants local time.
                text = formatLocalTime(note.createdAt) ?: note.createdAt,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.sync_test_delete),
            )
        }
    }
}

@StringRes
private fun SyncTestStatus.labelRes(): Int = when (this) {
    SyncTestStatus.Connected -> R.string.sync_test_status_connected
    SyncTestStatus.Connecting -> R.string.sync_test_status_connecting
    SyncTestStatus.Offline -> R.string.sync_test_status_offline
}

/** The phone's time zone and locale, so "10:42" means what the user expects. */
private fun localTimeFormatter(): DateTimeFormatter =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())

private fun formatLocalTime(instant: kotlin.time.Instant): String =
    localTimeFormatter().format(Instant.ofEpochMilli(instant.toEpochMilliseconds()))

/** Formats an ISO-8601 UTC timestamp, or null if the row holds something else. */
private fun formatLocalTime(isoUtc: String): String? = runCatching {
    localTimeFormatter().format(Instant.parse(isoUtc))
}.getOrNull()
