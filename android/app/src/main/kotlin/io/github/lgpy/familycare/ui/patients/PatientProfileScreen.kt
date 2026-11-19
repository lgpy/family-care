package io.github.lgpy.familycare.ui.patients

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.PatientExportFormat
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.patient.PatientHeaders
import io.github.lgpy.familycare.core.ui.patient.PatientTabIcon
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
    val profileTabs = contributed.map { tab ->
        ProfileTab(id = tab.id, label = stringResource(tab.titleRes), icon = tab.icon)
    } + ProfileTab(
        id = HISTORY_TAB_ID,
        label = stringResource(R.string.patient_tab_history),
        icon = PatientTabIcon.HISTORY,
    )

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
                age = patientAge(uiState.birthYear),
                reminderSummary = reminderSummary(
                    policy = uiState.reminderPolicy,
                    primaryCaregiverName = uiState.primaryCaregiverName,
                    primaryCaregiverIsMe = uiState.primaryCaregiverIsMe,
                ),
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
                // Fixed equal-width tabs while all four labels fit their
                // quarter at the current font; four labels in a scrollable row
                // push "History" past the edge, so the last tab disappears
                // behind a scroll nobody expects. When a label no longer fits
                // (a longer translation, a larger font) the row scrolls again
                // rather than clipping the word.
                // Each tab carries its icon above the label, filled while
                // selected (screens v2, design-system §6).
                ProfileTabRow(
                    tabs = profileTabs,
                    selectedId = if (historySelected) HISTORY_TAB_ID else selectedTabId,
                    onSelect = { selectedTabId = it },
                )

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
                imageVector = FcIcons.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // Screens v2: editing is an icon in the top bar, not only a menu item.
        if (canWrite) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = FcIcons.Edit,
                    contentDescription = stringResource(R.string.patient_profile_edit),
                )
            }
        }
        if (canWrite || canManage || canExport) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = FcIcons.MoreVert,
                        contentDescription = stringResource(R.string.patient_profile_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
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
 * The profile head (screens v2): the 60 dp identity avatar beside the patient's
 * name, and two short lines under it — the age, and how reminders are routed.
 *
 * The second line is one glanceable phrase with an alarm icon instead of the
 * full policy wording (which took four lines at 200% font): see
 * [reminderSummaryKind]. It wraps at word boundaries like the name above it.
 */
@Composable
private fun PatientProfileHead(
    patientId: PatientId,
    displayName: String,
    age: Int?,
    reminderSummary: String?,
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
            size = 60.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            age?.let { years ->
                Text(
                    text = pluralStringResource(R.plurals.patient_age_years, years, years),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            reminderSummary?.let { summary ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = FcIcons.Alarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.bodyMedium)),
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The one-line reminder summary of the policy, or the policy's own label when
 * it has no short human phrase; null while the patient is not loaded.
 */
@Composable
private fun reminderSummary(
    policy: ReminderPolicy?,
    primaryCaregiverName: String?,
    primaryCaregiverIsMe: Boolean,
): String? {
    val firstName = firstNameOf(primaryCaregiverName)
    return when (reminderSummaryKind(policy, firstName, primaryCaregiverIsMe)) {
        null -> null
        ReminderSummaryKind.PRIMARY_FIRST ->
            stringResource(R.string.patients_reminder_primary_first, firstName.orEmpty())

        ReminderSummaryKind.YOU_FIRST -> stringResource(R.string.patients_reminder_you_first)
        ReminderSummaryKind.EVERYONE -> stringResource(R.string.patients_reminder_everyone)
        ReminderSummaryKind.POLICY -> stringResource(policy!!.labelRes())
    }
}

/** The outlined icon of a tab, and its filled variant while it is selected. */
@Composable
private fun PatientTabIcon.image(selected: Boolean): ImageVector = when (this) {
    PatientTabIcon.MEDICATION -> if (selected) FcIcons.MedicationFilled else FcIcons.Medication
    PatientTabIcon.EVENT -> if (selected) FcIcons.EventFilled else FcIcons.Event
    PatientTabIcon.BADGE -> if (selected) FcIcons.BadgeFilled else FcIcons.Badge
    PatientTabIcon.HISTORY -> if (selected) FcIcons.HistoryFilled else FcIcons.History
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
        contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
    ) {
        val lastEntryId = history.lastOrNull()?.entries?.lastOrNull()?.id
        history.forEach { group ->
            item(key = "month-${group.month}") {
                Text(
                    text = monthLabel(group.month),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(group.entries, key = { it.id }) { entry ->
                TimelineRow(
                    entry = entry,
                    last = entry.id == lastEntryId,
                    onNavigate = onNavigate,
                )
            }
        }
    }
}

/**
 * One History event (screens v2): a 36 dp round icon tile, the title and detail,
 * the date, and the line that connects it to the next event.
 *
 * The tile is the entry's kind, so the timeline is scannable without reading;
 * the line is drawn under the tile and stops at the last entry.
 */
@Composable
private fun TimelineRow(
    entry: TimelineEntry,
    last: Boolean,
    onNavigate: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val promoted = entry.kind.isPromoted()
    val route = entry.route
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (route == null) Modifier else Modifier.clickable { onNavigate(route) },
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (promoted) scheme.primaryContainer else scheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.kind.icon(),
                    contentDescription = null,
                    tint = if (promoted) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (!last) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp)
                        .width(2.dp)
                        .background(scheme.outlineVariant),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
            )
            entry.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = relativeDate(entry.at),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 14.dp),
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

/** The tile's picture: one per timeline kind (screens v2). */
@Composable
private fun TimelineKind.icon(): ImageVector = when (this) {
    TimelineKind.MEDICATION_STARTED -> FcIcons.AddCircle
    TimelineKind.MEDICATION_CHANGED -> FcIcons.Medication
    TimelineKind.MEDICATION_STOPPED -> FcIcons.Block
    TimelineKind.STOCK -> FcIcons.Inventory2
    TimelineKind.APPOINTMENT_CONSULTATION -> FcIcons.Stethoscope
    TimelineKind.APPOINTMENT_BLOOD_TEST -> FcIcons.Bloodtype
    TimelineKind.APPOINTMENT_EXAM -> FcIcons.Radiology
    TimelineKind.APPOINTMENT_CHECKUP -> FcIcons.Stethoscope
    TimelineKind.APPOINTMENT_OTHER -> FcIcons.Event
    TimelineKind.OTHER -> FcIcons.Info
}

/**
 * Whether the entry is a change the person should notice (a regimen started or
 * changed), which gets the primary-container tile; everything else stays
 * neutral (screens v2, the mockup's `tile pri`).
 */
private fun TimelineKind.isPromoted(): Boolean = when (this) {
    TimelineKind.MEDICATION_STARTED,
    TimelineKind.MEDICATION_CHANGED,
    -> true

    else -> false
}

/** One tab of the profile: the contributed ones, then the app-owned History. */
private data class ProfileTab(
    val id: String,
    val label: String,
    val icon: PatientTabIcon,
)

/** Breathing room a fixed tab needs on each side of its label. */
private val FixedTabLabelGap = 8.dp

/**
 * Whether [labelWidthsPx] all fit their equal share of [availableWidthPx],
 * with [tabPaddingPx] of breathing room on each side.
 *
 * Pure and pixel-based on purpose: the decision is a measurement of the labels
 * in the user's font, never `fontScale` (docs/ux/design-system.md §1). An
 * empty list fits — there is nothing to cut.
 */
internal fun fixedTabsFit(
    availableWidthPx: Int,
    labelWidthsPx: List<Int>,
    tabPaddingPx: Int,
): Boolean {
    if (labelWidthsPx.isEmpty()) return true
    if (availableWidthPx <= 0) return false
    val perTab = availableWidthPx.toFloat() / labelWidthsPx.size
    return labelWidthsPx.all { width -> width + tabPaddingPx <= perTab }
}

/**
 * The profile's tab strip: four equal-width tabs while every label fits its
 * quarter, otherwise the scrollable row.
 *
 * With a fixed row "History" is always on screen; the scrollable row pushed it
 * past the right edge even at 100%, so the fourth tab looked missing. When a
 * label genuinely does not fit (a longer translation, or 200% font) scrolling
 * is the honest fallback: the labels stay whole instead of being trimmed.
 */
@Composable
private fun ProfileTabRow(
    tabs: List<ProfileTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.titleSmall

    // The row's own width, not the screen's: a split-screen window measures its
    // quarter from what the tabs actually get.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth
        val fixed = remember(measurer, tabs, style, rowWidth, density) {
            val labelWidths = tabs.map { tab ->
                measurer.measure(text = tab.label, style = style, maxLines = 1).size.width
            }
            with(density) {
                fixedTabsFit(
                    availableWidthPx = rowWidth.roundToPx(),
                    labelWidthsPx = labelWidths,
                    tabPaddingPx = FixedTabLabelGap.roundToPx(),
                )
            }
        }
        val selectedIndex = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

        val tabContent: @Composable () -> Unit = {
            tabs.forEach { tab ->
                val selected = tab.id == selectedId
                Tab(
                    selected = selected,
                    onClick = { onSelect(tab.id) },
                    icon = {
                        Icon(
                            imageVector = tab.icon.image(selected),
                            contentDescription = null,
                        )
                    },
                    text = { Text(text = tab.label) },
                )
            }
        }

        if (fixed) {
            PrimaryTabRow(selectedTabIndex = selectedIndex) { tabContent() }
        } else {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = ProfileTabEdgePadding,
            ) { tabContent() }
        }
    }
}

/** The scrollable row's edge padding: the first tab lines up with the head. */
private val ProfileTabEdgePadding = 16.dp
