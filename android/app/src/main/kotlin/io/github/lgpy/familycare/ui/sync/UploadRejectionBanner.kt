package io.github.lgpy.familycare.ui.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.sync.RejectedChange
import io.github.lgpy.familycare.core.domain.sync.RejectedUpload
import io.github.lgpy.familycare.core.domain.sync.RejectionReason
import io.github.lgpy.familycare.core.ui.R
import org.koin.compose.viewmodel.koinViewModel

/**
 * A persistent, non-blocking bar at the top of the app shell: a local change
 * was refused by the server and has been undone.
 *
 * It is deliberately not a snackbar or a dialog: the change is already lost, so
 * the message must stay until the user reads it and dismisses it, on every tab.
 * It shows nothing at all while there is no refusal, so offline or transient
 * sync errors keep whatever indicator they already have.
 */
@Composable
fun UploadRejectionBanner(
    modifier: Modifier = Modifier,
    viewModel: UploadRejectionViewModel = koinViewModel(),
) {
    val rejection by viewModel.rejection.collectAsState()
    val current = rejection ?: return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.message(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            TextButton(onClick = viewModel::dismiss) {
                Text(text = stringResource(R.string.sync_rejected_dismiss))
            }
        }
    }
}

/**
 * "A change to a patient couldn’t be saved on the server and was undone (not
 * allowed)." The target and the reason are their own resources, so both
 * languages read naturally without duplicating the sentence nine times.
 */
@Composable
private fun RejectedUpload.message(): String = stringResource(
    R.string.sync_rejected_change,
    stringResource(change.subjectRes()),
    stringResource(reason.labelRes()),
)

@StringRes
private fun RejectedChange.subjectRes(): Int = when (this) {
    RejectedChange.PATIENT -> R.string.sync_rejected_subject_patient
    RejectedChange.PROFILE -> R.string.sync_rejected_subject_profile
    RejectedChange.CARE_CIRCLE -> R.string.sync_rejected_subject_circle
    RejectedChange.OTHER -> R.string.sync_rejected_subject_other
}

@StringRes
private fun RejectionReason.labelRes(): Int = when (this) {
    RejectionReason.NOT_ALLOWED -> R.string.sync_rejected_reason_not_allowed
    RejectionReason.INVALID_DATA -> R.string.sync_rejected_reason_invalid
    RejectionReason.OTHER -> R.string.sync_rejected_reason_other
}
