package io.github.lgpy.familycare.ui.reminders

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import io.github.lgpy.familycare.feature.medications.reminders.DoseReminderScheduler
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealth
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealthChecker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Which instructions to show (docs/build-plan.md §8). */
enum class PhoneBrand {
    HONOR,
    SAMSUNG,
    OTHER,
    ;

    companion object {
        fun detect(manufacturer: String): PhoneBrand {
            val name = manufacturer.lowercase()
            return when {
                name.contains("honor") || name.contains("huawei") -> HONOR
                name.contains("samsung") -> SAMSUNG
                else -> OTHER
            }
        }
    }
}

/**
 * More → Reminders: "Make reminders reliable" (docs/build-plan.md §8, D19).
 *
 * The app verifies what Android lets it verify — notifications, the channel,
 * exact alarms, battery optimisation and whether the scheduled alarms match the
 * plan — and shows a ✓/✗ for each. Brand-specific steps that only a person can
 * do are checkboxes stored on this phone.
 *
 * Every settings intent is wrapped: when a vendor activity is missing or
 * disabled, the app falls back to the system app-details screen instead of
 * crashing.
 */
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val checker = koinInject<ReminderHealthChecker>()
    val scheduler = koinInject<DoseReminderScheduler>()
    val scope = rememberCoroutineScope()
    val brand = remember { PhoneBrand.detect(Build.MANUFACTURER.orEmpty()) }
    val checklist = remember { ReminderChecklist(context) }

    var health by remember { mutableStateOf<ReminderHealth?>(null) }
    var manual by remember { mutableStateOf(checklist.read()) }

    fun refresh(reschedule: Boolean = false) {
        scope.launch {
            if (reschedule) runCatching { scheduler.reschedule() }
            health = checker.check()
        }
    }

    LaunchedEffect(Unit) { refresh(reschedule = true) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refresh()
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
                    imageVector = FcIcons.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                ScreenHeader(title = stringResource(R.string.reminders_title))
            }
        }
        Text(
            text = stringResource(R.string.reminders_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = stringResource(R.string.reminders_checks_title),
            style = MaterialTheme.typography.titleMedium,
        )
        val current = health
        if (current == null) {
            Text(text = stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
        } else {
            CheckRow(
                statusOn = stringResource(R.string.reminders_check_notifications_on),
                statusOff = stringResource(R.string.reminders_check_notifications_off),
                ok = current.notificationsAllowed,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings(context)
                }
            }
            CheckRow(
                statusOn = stringResource(R.string.reminders_check_channel_on),
                statusOff = stringResource(R.string.reminders_check_channel_off),
                ok = current.channelEnabled,
            ) { openNotificationSettings(context) }
            CheckRow(
                statusOn = stringResource(R.string.reminders_check_exact_alarms_on),
                statusOff = stringResource(R.string.reminders_check_exact_alarms_off),
                ok = current.exactAlarmsAllowed,
            ) { requestExactAlarms(context) }
            CheckRow(
                statusOn = stringResource(R.string.reminders_check_battery_on),
                statusOff = stringResource(R.string.reminders_check_battery_off),
                ok = current.batteryOptimisationIgnored,
            ) { requestIgnoreBatteryOptimisation(context) }
            CheckRow(
                statusOn = stringResource(
                    R.string.reminders_check_alarms_on,
                    current.plannedCount,
                ),
                statusOff = stringResource(
                    R.string.reminders_check_alarms_off,
                    current.scheduledCount,
                    current.plannedCount,
                ),
                ok = current.alarmsInSync,
            ) { refresh(reschedule = true) }
            // M4/D27: informational only — escalation is the server-side safety
            // net, so an unregistered phone still reminds locally.
            CheckRow(
                statusOn = stringResource(R.string.reminders_check_push_on),
                statusOff = stringResource(R.string.reminders_check_push_off),
                ok = current.pushRegistered,
            )
            Text(
                text = current.nextAlarmAt?.let { nextAlarmText(context, it) }
                    ?: stringResource(R.string.reminders_no_next_alarm),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (current.isHealthy) {
                Text(
                    text = stringResource(R.string.reminders_all_good),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(
                when (brand) {
                    PhoneBrand.HONOR -> R.string.reminders_honor_title
                    PhoneBrand.SAMSUNG -> R.string.reminders_samsung_title
                    PhoneBrand.OTHER -> R.string.reminders_other_title
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                when (brand) {
                    PhoneBrand.HONOR -> R.string.reminders_honor_steps
                    PhoneBrand.SAMSUNG -> R.string.reminders_samsung_steps
                    PhoneBrand.OTHER -> R.string.reminders_other_steps
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        when (brand) {
            PhoneBrand.HONOR -> TextButton(onClick = { openHonorStartup(context) }) {
                Text(text = stringResource(R.string.reminders_open_startup_settings))
            }

            PhoneBrand.SAMSUNG -> TextButton(onClick = { openSamsungBattery(context) }) {
                Text(text = stringResource(R.string.reminders_open_battery_settings))
            }

            else -> Unit
        }
        TextButton(onClick = { openAppDetails(context) }) {
            Text(text = stringResource(R.string.reminders_open_app_details))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.reminders_manual_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.reminders_manual_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        manualSteps(brand).forEach { step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = manual[step] == true,
                        onValueChange = { checked ->
                            manual = checklist.set(step, checked)
                        },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = manual[step] == true, onCheckedChange = null)
                Text(
                    text = stringResource(step),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = { refresh(reschedule = true) }) {
            Text(text = stringResource(R.string.reminders_refresh))
        }
    }
}

@Composable
private fun CheckRow(
    statusOn: String,
    statusOff: String,
    ok: Boolean,
    onFix: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (ok) "✓" else "✗",
            style = MaterialTheme.typography.titleMedium,
            color = if (ok) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = if (ok) statusOn else statusOff,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.weight(1f),
        )
        if (!ok && onFix != null) {
            FilledTonalButton(
                onClick = onFix,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.reminders_fix))
            }
        }
    }
}

@Composable
private fun manualSteps(brand: PhoneBrand): List<Int> = when (brand) {
    PhoneBrand.HONOR -> listOf(
        R.string.reminders_manual_launch,
        R.string.reminders_manual_background,
        R.string.reminders_manual_battery,
        R.string.reminders_manual_lockscreen,
    )

    PhoneBrand.SAMSUNG -> listOf(
        R.string.reminders_manual_battery,
        R.string.reminders_manual_never_sleeping,
        R.string.reminders_manual_unused_apps,
        R.string.reminders_manual_lockscreen,
    )

    PhoneBrand.OTHER -> listOf(
        R.string.reminders_manual_battery,
        R.string.reminders_manual_lockscreen,
    )
}

/** The manual checklist, stored on this phone only (no sync, no backup). */
private class ReminderChecklist(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): Map<Int, Boolean> =
        preferences.all.mapNotNull { (key, value) ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            id to (value as? Boolean ?: false)
        }.toMap()

    fun set(step: Int, checked: Boolean): Map<Int, Boolean> {
        preferences.edit().putBoolean(step.toString(), checked).apply()
        return read()
    }

    private companion object {
        const val PREFERENCES_NAME = "reminder_setup_checklist"
    }
}

private fun nextAlarmText(context: Context, at: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("EEE HH:mm")
    val local = at.atZone(ZoneId.systemDefault()).format(formatter)
    return context.getString(R.string.reminders_next_alarm, local)
}

// --- Settings intents, every one with a safe fallback. -----------------------

private fun openNotificationSettings(context: Context) = guarded(context) {
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

private fun requestExactAlarms(context: Context) = guarded(context) {
    Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}"),
    )
}

private fun requestIgnoreBatteryOptimisation(context: Context) = guarded(context) {
    @Suppress("BatteryLife")
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
}

private fun openAppDetails(context: Context) = guarded(context) {
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
}

/**
 * Honor/MagicOS startup manager. The component differs between MagicOS
 * versions; each candidate is tried, then the app-details screen. There is no
 * stable public API, which is why the manual checklist exists beside it.
 */
private fun openHonorStartup(context: Context) {
    val candidates = listOf(
        ComponentName(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
    )
    for (component in candidates) {
        val started = runCatching {
            context.startActivity(Intent().setComponent(component))
        }.isSuccess
        if (started) return
    }
    openAppDetails(context)
}

/** Samsung One UI battery settings, with a system fallback. */
private fun openSamsungBattery(context: Context) {
    runCatching {
        context.startActivity(
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            ),
        )
    }.onSuccess { return }
    runCatching {
        context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }.onSuccess { return }
    openAppDetails(context)
}

private fun guarded(context: Context, build: () -> Intent) {
    runCatching { context.startActivity(build()) }
        .onFailure { openAppDetails(context) }
}
