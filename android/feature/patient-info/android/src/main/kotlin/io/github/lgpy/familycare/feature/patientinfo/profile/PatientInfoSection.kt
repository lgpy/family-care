package io.github.lgpy.familycare.feature.patientinfo.profile

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContact
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Info tab of the patient profile (screens v2, docs/domain/patient-info.md
 * "Screens"): one icon-led row for every field the record stores outside the
 * allergies and conditions the header already shows.
 *
 * Numbers are trailing buttons that open the dialler through `ACTION_DIAL` —
 * the app never places a call itself, so the user always confirms. The SNS
 * number can be copied; on Android 13+ the clip is marked sensitive so the
 * system hides it from clipboard previews. "Edit info" is shown for owners and
 * caregivers only, the same [io.github.lgpy.familycare.core.domain.circle.canWrite]
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!uiState.loaded) {
            item { CircularProgressIndicator() }
            return@LazyColumn
        }

        if (!uiState.isEmpty && !uiState.hasDetails) {
            // Allergies and conditions live in the header above the tabs, so
            // this tab can have nothing else: say so instead of showing only
            // "Edit".
            item {
                Text(
                    text = stringResource(R.string.patient_info_no_details),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (uiState.isEmpty) {
            item {
                Text(
                    text = stringResource(R.string.patient_info_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val info = uiState.info
        if (info != null && uiState.hasDetails) {
            if (info.emergencyContacts.isNotEmpty()) {
                itemsIndexed(info.emergencyContacts) { _, contact ->
                    ContactInfoRow(
                        contact = contact,
                        onDial = { dial(context, contact.phone) },
                    )
                }
            }

            val doctor = info.familyDoctor?.takeIf { it.isNotBlank() }
            val healthCentre = info.healthCentre?.takeIf { it.isNotBlank() }
            val healthCentrePhone = info.healthCentrePhone?.takeIf { it.isNotBlank() }
            if (doctor != null || healthCentre != null || healthCentrePhone != null) {
                item {
                    InfoRow(
                        icon = FcIcons.Stethoscope,
                        label = if (doctor != null) {
                            stringResource(R.string.patient_info_doctor_label)
                        } else {
                            stringResource(R.string.patient_info_centre_label)
                        },
                        value = listOfNotNull(doctor, healthCentre, healthCentrePhone)
                            .joinToString(stringResource(R.string.patient_info_list_separator)),
                        trailing = healthCentrePhone?.let { phone ->
                            {
                                IconButton(onClick = { dial(context, phone) }) {
                                    Icon(
                                        imageVector = FcIcons.Call,
                                        contentDescription = stringResource(
                                            R.string.patient_info_call,
                                            stringResource(R.string.patient_info_centre_label),
                                        ),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            info.snsNumber?.takeIf { it.isNotBlank() }?.let { sns ->
                item {
                    InfoRow(
                        icon = FcIcons.Badge,
                        label = stringResource(R.string.patient_info_sns_label),
                        value = sns,
                        trailing = {
                            IconButton(onClick = { copySensitive(context, sns) }) {
                                Icon(
                                    imageVector = FcIcons.ContentCopy,
                                    contentDescription = stringResource(
                                        R.string.patient_info_copy_sns,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }

            info.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                item {
                    InfoRow(
                        icon = FcIcons.StickyNote2,
                        label = stringResource(R.string.patient_info_notes_title),
                        value = notes,
                    )
                }
            }
        }

        if (uiState.canWrite) {
            item {
                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = FcIcons.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.patient_info_edit),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * One emergency contact: the alert tile (the only filled one, primary
 * container, as in the mockup), the name and relationship, the number and a
 * trailing Call button.
 */
@Composable
private fun ContactInfoRow(contact: EmergencyContact, onDial: () -> Unit) {
    val details = listOfNotNull(
        contact.relationship?.takeIf { it.isNotBlank() },
        contact.phone,
    ).joinToString(stringResource(R.string.patient_info_list_separator))

    InfoRow(
        icon = FcIcons.EmergencyFilled,
        label = stringResource(R.string.patient_info_contacts_title),
        value = contact.name,
        detail = details,
        primaryTile = true,
        trailing = {
            IconButton(onClick = onDial) {
                Icon(
                    imageVector = FcIcons.CallFilled,
                    contentDescription = stringResource(
                        R.string.patient_info_call,
                        contact.name,
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/**
 * One row: a 40 dp rounded icon tile, a bold label, the value (and an optional
 * second line) and an optional trailing action.
 */
@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    primaryTile: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val tileColor = if (primaryTile) scheme.primaryContainer else scheme.surfaceContainerHigh
    val iconColor = if (primaryTile) scheme.onPrimaryContainer else scheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tileColor),
            contentAlignment = Alignment.Center,
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
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            detail?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Puts the SNS number on the clipboard and marks it sensitive (Android 13+),
 * so the system's clipboard preview does not reveal it, then confirms with a
 * short toast.
 */
private fun copySensitive(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(
        context.getString(R.string.patient_info_sns_label),
        value,
    )
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(
        context,
        R.string.patient_info_sns_copied,
        Toast.LENGTH_SHORT,
    ).show()
}

/** Opens the dialler on [phone]; the user still has to start the call. */
private fun dial(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)))
    runCatching { context.startActivity(intent) }
}
