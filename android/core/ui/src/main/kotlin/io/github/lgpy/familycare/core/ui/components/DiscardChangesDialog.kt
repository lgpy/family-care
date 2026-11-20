package io.github.lgpy.familycare.core.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lgpy.familycare.core.ui.R

/**
 * The confirmation every form shows when it would lose what the user typed:
 * a close button or the system back gesture asks first (screens v2).
 *
 * The screen owns *when* the question is asked — it tracks whether anything
 * changed and calls this only then — and what "Discard" does; the dialog itself
 * only offers the two answers. Keeping it here means Add patient, Edit patient,
 * New medicine, Edit medicine and the dose sheet ask with the same words.
 *
 * @param onKeepEditing The user changed their mind: stay on the form.
 * @param onDiscard The user accepts losing the changes: close the form.
 */
@Composable
fun DiscardChangesDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(text = stringResource(R.string.discard_changes_title)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(text = stringResource(R.string.discard_changes_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(text = stringResource(R.string.discard_changes_keep_editing))
            }
        },
    )
}
