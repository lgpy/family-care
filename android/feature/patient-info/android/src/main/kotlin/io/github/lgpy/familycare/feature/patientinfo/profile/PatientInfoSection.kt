package io.github.lgpy.familycare.feature.patientinfo.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContact
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Info tab of the patient profile (docs/domain/patient-info.md "Screens"):
 * the SNS number, the family doctor, the health centre and its phone, the
 * emergency contacts and the notes.
 *
 * Numbers are tappable and open the dialler through `ACTION_DIAL` — the app
 * never places a call itself, so the user always confirms. "Edit" is shown for
 * owners and caregivers only, the same [io.github.lgpy.familycare.core.domain.circle.canWrite]
 * rule the repository enforces.
 *
 * It has no Scaffold of its own: the profile owns the screen.
 */
@Composable
fun PatientInfoSection(
    patientId: String,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientInfoViewModel>(
        parameters = { parametersOf(patientId) },
    )
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.loaded && uiState.canWrite) {
            item {
                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
                    Text(
                        text = stringResource(R.string.patient_info_edit),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        if (!uiState.loaded) {
            item { CircularProgressIndicator() }
            return@LazyColumn
        }

        when {
            uiState.isEmpty -> {
                item {
                    Text(
                        text = stringResource(R.string.patient_info_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // Allergies/conditions live in the header above the tabs, so this
            // tab can have nothing else: say so instead of showing only "Edit".
            !uiState.hasDetails -> {
                item {
                    Text(
                        text = stringResource(R.string.patient_info_no_details),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            else -> {
                val info = uiState.info ?: return@LazyColumn

                info.snsNumber?.let { sns ->
                    item {
                        InfoRow(label = stringResource(R.string.patient_info_sns_label), value = sns)
                    }
                }
                info.familyDoctor?.let { doctor ->
                    item {
                        InfoRow(label = stringResource(R.string.patient_info_doctor_label), value = doctor)
                    }
                }
                if (info.healthCentre != null || info.healthCentrePhone != null) {
                    item {
                        InfoRow(
                            label = stringResource(R.string.patient_info_centre_label),
                            value = info.healthCentre.orEmpty(),
                            detail = info.healthCentrePhone,
                            onDial = info.healthCentrePhone?.let { phone ->
                                { dial(context, phone) }
                            },
                        )
                    }
                }

                if (info.emergencyContacts.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.patient_info_contacts_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    itemsIndexed(info.emergencyContacts) { _, contact ->
                        ContactRow(
                            contact = contact,
                            onDial = { dial(context, contact.phone) },
                        )
                    }
                }

                info.notes?.let { notes ->
                    item {
                        InfoRow(label = stringResource(R.string.patient_info_notes_title), value = notes)
                    }
                }
            }
        }
    }
}

/** One labelled line; a phone number is a button that opens the dialler. */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    detail: String? = null,
    onDial: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        if (value.isNotBlank()) {
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
        detail?.let { phone ->
            if (onDial == null) {
                Text(text = phone, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    text = phone,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onDial)
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

/** One emergency contact: name and relationship, then the dialable number. */
@Composable
private fun ContactRow(contact: EmergencyContact, onDial: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = contact.name, style = MaterialTheme.typography.bodyLarge)
            contact.relationship?.let { relationship ->
                Text(text = relationship, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = contact.phone,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onDial)
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
        )
    }
}

/** Opens the dialler on [phone]; the user still has to start the call. */
private fun dial(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)))
    runCatching { context.startActivity(intent) }
}
