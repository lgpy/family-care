package io.github.lgpy.familycare.ui.patients

import androidx.compose.foundation.clickable
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.PatientExportFormat
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.patient.PatientHeaders
import io.github.lgpy.familycare.core.ui.patient.PatientTabs
import io.github.lgpy.familycare.core.ui.patient.TimelineEntry
import io.github.lgpy.familycare.core.ui.patient.TimelineKind
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The app-owned History tab's id; no module may claim it. */
private const val HISTORY_TAB_ID: String = "history"

/**
 * The patient profile (D29, docs/ux/screens.md §3): the person's name, the
 * contributed headers (patient-info's allergies banner next), the contributed
 * tabs in order, and the app-owned History tab last.
 *
 * The screen is the shell's, but everything inside comes from the extension
 * points in `core/ui`; the app names no feature screen (D5). Editing the
 * patient is a menu action, as decided in D29; the tab content opens its own
 * routes through [onNavigate].
 */
@Composable
fun PatientProfileScreen(
    patientId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientProfileViewModel>(
        parameters = { parametersOf(patientId) },
    )
    val uiState by viewModel.uiState.collectAsState()
    val headers = koinInject<PatientHeaders>()
    val tabs = koinInject<PatientTabs>()
    val patient = PatientId(patientId)

    val contributed = tabs.ordered
    var selectedTabId by rememberSaveable(patientId) {
        mutableStateOf(contributed.firstOrNull()?.id ?: HISTORY_TAB_ID)
    }
    val selectedIndex = contributed.indexOfFirst { it.id == selectedTabId }
    val historySelected = selectedIndex < 0
    val tabIndex = if (historySelected) contributed.size else selectedIndex

    var confirmingArchive by remember { mutableStateOf(false) }
    var confirmingUnarchive by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var typedName by remember { mutableStateOf("") }
    var choosingExport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.export_share_title)

    // D30: the generated file is handed to the share sheet once, through the
    // FileProvider that exposes only cache/exports.
    LaunchedEffect(uiState.exportShare) {
        val share = uiState.exportShare ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            share.file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = share.format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, shareTitle),
        )
        viewModel.onExportShared()
    }

    // D30: the server deleted the patient; the list is where the user goes next.
    LaunchedEffect(uiState.erased) {
        if (uiState.erased) onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        PatientProfileTopBar(
            canWrite = uiState.canWrite,
            canManage = uiState.canManage && !uiState.notFound,
            canExport = uiState.canExport,
            isArchived = uiState.isArchived,
            onBack = onBack,
            onEdit = { onEdit(patientId) },
            onArchive = { confirmingArchive = true },
            onUnarchive = { confirmingUnarchive = true },
            onExport = { choosingExport = true },
            onDelete = {
                typedName = ""
                deleting = true
            },
        )

        if (uiState.loaded && !uiState.notFound && uiState.displayName.isNotBlank()) {
            PatientProfileHead(
                patientId = patient,
                displayName = uiState.displayName,
            )
        }

        if (uiState.isArchived) {
            Text(
                text = stringResource(R.string.patient_archived_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        uiState.actionError?.let { error ->
            Text(
                text = stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        uiState.exportError?.let { error ->
            Text(
                text = stringResource(error.exportMessageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { viewModel.clearExportError() },
            )
        }

        when {
            !uiState.loaded -> CircularProgressIndicator(
                modifier = Modifier.padding(24.dp),
            )

            uiState.notFound -> Text(
                text = stringResource(R.string.patient_not_available),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(24.dp),
            )

            else -> {
                headers.ordered.forEach { header ->
                    header.Content(patientId = patient, onNavigate = onNavigate)
                }

                // History is the shell's tab, so it is drawn after the
                // contributions rather than registered as one.
                // The profile's tabs span the screen and scroll to its edges;
                // 16 dp edge padding lines the first tab up with the head above,
                // and a long label is never clipped inside a content padding.
                PrimaryScrollableTabRow(
                    selectedTabIndex = tabIndex,
                    edgePadding = 16.dp,
                ) {
                    contributed.forEach { tab ->
                        Tab(
                            selected = !historySelected && tab.id == selectedTabId,
                            onClick = { selectedTabId = tab.id },
                            text = { Text(text = stringResource(tab.titleRes)) },
                        )
                    }
                    Tab(
                        selected = historySelected,
                        onClick = { selectedTabId = HISTORY_TAB_ID },
                        text = { Text(text = stringResource(R.string.patient_tab_history)) },
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    val tab = contributed.firstOrNull { it.id == selectedTabId }
                    if (historySelected || tab == null) {
                        HistoryList(
                            history = uiState.history,
                            onNavigate = onNavigate,
                        )
                    } else {
                        tab.Content(patientId = patient, onNavigate = onNavigate)
                    }
                }
            }
        }
    }

    if (choosingExport) {
        AlertDialog(
            onDismissRequest = { if (!uiState.exporting) choosingExport = false },
            title = {
                Text(text = stringResource(R.string.export_dialog_title, uiState.displayName))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(R.string.export_dialog_body))
                    TextButton(
                        enabled = !uiState.exporting,
                        onClick = {
                            choosingExport = false
                            viewModel.export(PatientExportFormat.JSON)
                        },
                    ) {
                        Text(text = stringResource(R.string.export_format_json))
                    }
                    TextButton(
                        enabled = !uiState.exporting,
                        onClick = {
                            choosingExport = false
                            viewModel.export(PatientExportFormat.PDF)
                        },
                    ) {
                        Text(text = stringResource(R.string.export_format_pdf))
                    }
                    if (uiState.exporting) {
                        Text(text = stringResource(R.string.export_generating))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingExport = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmingArchive) {
        AlertDialog(
            onDismissRequest = { confirmingArchive = false },
            title = {
                Text(
                    text = stringResource(
                        R.string.patient_archive_confirm_title,
                        uiState.displayName,
                    ),
                )
            },
            text = { Text(text = stringResource(R.string.patient_archive_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingArchive = false
                        viewModel.archive()
                    },
                ) {
                    Text(text = stringResource(R.string.patient_archive_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingArchive = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmingUnarchive) {
        AlertDialog(
            onDismissRequest = { confirmingUnarchive = false },
            title = {
                Text(
                    text = stringResource(
                        R.string.patient_unarchive_confirm_title,
                        uiState.displayName,
                    ),
                )
            },
            text = { Text(text = stringResource(R.string.patient_unarchive_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingUnarchive = false
                        viewModel.unarchive()
                    },
                ) {
                    Text(text = stringResource(R.string.patient_unarchive_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnarchive = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    if (deleting) {
        val matches = typedName == uiState.displayName && uiState.displayName.isNotEmpty()
        AlertDialog(
            onDismissRequest = { if (!uiState.erasing) deleting = false },
            title = { Text(text = stringResource(R.string.patient_delete_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.patient_delete_warning))
                    OutlinedTextField(
                        value = typedName,
                        onValueChange = { typedName = it },
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.patient_delete_name_label,
                                    uiState.displayName,
                                ),
                            )
                        },
                        singleLine = true,
                        enabled = !uiState.erasing,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.erase() },
                    enabled = matches && !uiState.erasing,
                ) {
                    Text(text = stringResource(R.string.patient_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleting = false },
                    enabled = !uiState.erasing,
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** The one line a failed lifecycle action shows; never the server's text. */
@Composable
private fun CoreError.messageRes(): Int = when (this) {
    CoreError.Offline -> R.string.patient_action_offline
    CoreError.NotAllowed -> R.string.patient_action_not_allowed
    CoreError.NotFound -> R.string.patient_action_not_found
    else -> R.string.patient_action_failed
}

/** The export's own wording: offline is the expected failure, not a generic one. */
private fun CoreError.exportMessageRes(): Int = when (this) {
    CoreError.Offline -> R.string.export_error_offline
    CoreError.NotAllowed -> R.string.export_error_not_allowed
    else -> R.string.export_error_failed
}

@Composable
private fun PatientProfileTopBar(
    canWrite: Boolean,
    canManage: Boolean,
    canExport: Boolean,
    isArchived: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (canWrite || canManage || canExport) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.patient_profile_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (canWrite) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.patient_profile_edit)) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                    }
                    if (canManage) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(
                                        if (isArchived) {
                                            R.string.patient_unarchive_action
                                        } else {
                                            R.string.patient_archive_action
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                if (isArchived) onUnarchive() else onArchive()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.patient_delete_action)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                    if (canExport) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.patient_export_action)) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The profile head (docs/ux/mockups/scaling.html): the 56 dp identity avatar
 * beside the patient's name. The age line is omitted: the domain patient has no
 * date of birth, and inventing one would be clinical information.
 */
@Composable
private fun PatientProfileHead(
    patientId: PatientId,
    displayName: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PatientAvatar(
            patientId = patientId,
            name = displayName,
            size = 56.dp,
        )
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The History tab: every module's events, grouped by month, newest first. */
@Composable
private fun HistoryList(
    history: List<HistoryGroup>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.patient_history_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        history.forEach { group ->
            item(key = "month-${group.month}") {
                Text(
                    text = monthLabel(group.month),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(group.entries, key = { it.id }) { entry ->
                TimelineRow(entry = entry, onNavigate = onNavigate)
            }
        }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, onNavigate: (String) -> Unit) {
    val route = entry.route
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (route == null) Modifier else Modifier.clickable { onNavigate(route) },
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.kind.icon(),
            contentDescription = null,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.title, style = MaterialTheme.typography.bodyLarge)
            entry.detail?.let { detail ->
                Text(text = detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = relativeDate(entry.at),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** "Novembro de 2026" / "November 2026", in the user's language. */
@Composable
private fun monthLabel(month: YearMonth): String {
    val locale = LocalConfiguration.current.locales[0]
    val name = remember(month, locale) {
        month.month
            .getDisplayName(TextStyle.FULL, locale)
            .replaceFirstChar { it.titlecase(locale) }
    }
    return stringResource(R.string.patient_history_month, name, month.year)
}

/** "Today", "Yesterday", otherwise the localized date. */
@Composable
private fun relativeDate(at: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    val date = remember(at, zone) { at.atZone(zone).toLocalDate() }
    val today = LocalDate.now(zone)
    return when (date) {
        today -> stringResource(R.string.patient_history_today)
        today.minusDays(1) -> stringResource(R.string.patient_history_yesterday)
        else -> remember(date, locale) {
            date.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
            )
        }
    }
}

private fun TimelineKind.icon(): ImageVector = when (this) {
    TimelineKind.MEDICATION -> Icons.Filled.Favorite
    TimelineKind.APPOINTMENT -> Icons.Filled.DateRange
    TimelineKind.OTHER -> Icons.Filled.Info
}
