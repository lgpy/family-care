package io.github.lgpy.familycare.feature.medications.dashboard

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.ui.formatTime

/**
 * The safety check before a second confirmation: "Already given by Mom at 08:04.
 * Did you give another one?" (docs/ux/screens.md §2).
 *
 * Shared by the "Doses today" card and the "Needs attention" card, so the two
 * cannot drift apart.
 */
@Composable
internal fun AlreadyGivenDialog(
    group: DoseGroupUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = when {
        group.givenBy?.isMe == true -> stringResource(R.string.meds_you)
        else -> group.givenBy?.name ?: stringResource(R.string.meds_someone_else)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.meds_already_given_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.meds_already_given_body,
                    name,
                    group.at?.let(::formatTime).orEmpty(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.meds_give_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
