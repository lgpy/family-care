package io.github.lgpy.familycare.feature.medications.reminders

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.lgpy.familycare.core.ui.R
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Which of the two setup questions is showing. */
private enum class PromptStep {
    LOADING,
    NOTIFICATIONS,
    BATTERY,
    DONE,
}

/**
 * The first-run reminder setup offered right after the circle's first medicine
 * is saved (first-use audit, finding 4).
 *
 * Reliable reminders are the product's first principle, but before this prompt
 * the only place that requested `POST_NOTIFICATIONS` was the repair screen, so a
 * new user could schedule every dose and never see a notification. The prompt
 * asks once, in the moment reminders start to matter:
 *
 * 1. **Notifications.** On Android 13+ without the permission, the dialog
 *    explains that reminders would be scheduled but invisible. "Allow" runs the
 *    system permission dialog with [ActivityResultContracts.RequestPermission];
 *    "Not now" ends the prompt.
 * 2. **Battery.** Once notifications are allowed (or were already), and while
 *    the phone still optimises this app, one dialog offers the existing
 *    "Make reminders reliable" screen. It is offered **once**: the answer is
 *    stored on this phone, so the prompt never nags on every new medicine.
 *
 * The health check is the same [ReminderHealthChecker] the reliability screen
 * uses, so the prompt cannot disagree with what that screen reports. Everything
 * is device-local and carries no health data.
 *
 * @param onFinished Closes the form: the prompt has nothing more to ask.
 * @param onOpenReminderSetup Opens the shell's reliability screen. The shell
 *   owns that route, so the module never names the app's navigation.
 */
@Composable
internal fun FirstMedicineReminderPrompt(
    onFinished: () -> Unit,
    onOpenReminderSetup: () -> Unit,
) {
    val context = LocalContext.current
    val checker = koinInject<ReminderHealthChecker>()
    val scope = rememberCoroutineScope()
    val store = remember(context) { ReminderSetupPromptStore(context) }
    var step by remember { mutableStateOf(PromptStep.LOADING) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scope.launch {
            val health = checker.check()
            step = if (
                granted &&
                !health.batteryOptimisationIgnored &&
                !store.batteryOffered
            ) {
                PromptStep.BATTERY
            } else {
                PromptStep.DONE
            }
        }
    }

    LaunchedEffect(Unit) {
        val health = checker.check()
        step = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !health.notificationsAllowed -> PromptStep.NOTIFICATIONS

            !health.batteryOptimisationIgnored && !store.batteryOffered -> PromptStep.BATTERY
            else -> PromptStep.DONE
        }
    }

    LaunchedEffect(step) {
        if (step == PromptStep.DONE) onFinished()
    }

    when (step) {
        PromptStep.NOTIFICATIONS -> AlertDialog(
            onDismissRequest = { step = PromptStep.DONE },
            title = { Text(text = stringResource(R.string.reminders_permission_title)) },
            text = { Text(text = stringResource(R.string.reminders_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                ) {
                    Text(text = stringResource(R.string.reminders_permission_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { step = PromptStep.DONE }) {
                    Text(text = stringResource(R.string.reminders_permission_not_now))
                }
            },
        )

        PromptStep.BATTERY -> AlertDialog(
            onDismissRequest = {
                store.markBatteryOffered()
                step = PromptStep.DONE
            },
            title = { Text(text = stringResource(R.string.reminders_battery_title)) },
            text = { Text(text = stringResource(R.string.reminders_battery_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.markBatteryOffered()
                        onOpenReminderSetup()
                    },
                ) {
                    Text(text = stringResource(R.string.reminders_battery_open))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        store.markBatteryOffered()
                        step = PromptStep.DONE
                    },
                ) {
                    Text(text = stringResource(R.string.reminders_battery_later))
                }
            },
        )

        PromptStep.LOADING,
        PromptStep.DONE,
        -> Unit
    }
}

/**
 * Whether the one-time battery offer has already been shown on this phone.
 *
 * Kept in its own preferences file (no sync, no backup) like the manual
 * checklist on the reliability screen: it describes this install's setup, not
 * the care circle. The flag is written when the dialog is answered, so a
 * rotation while it is open does not silently drop the offer.
 */
private class ReminderSetupPromptStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val batteryOffered: Boolean
        get() = preferences.getBoolean(KEY_BATTERY_OFFERED, false)

    fun markBatteryOffered() {
        preferences.edit().putBoolean(KEY_BATTERY_OFFERED, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "first_medicine_reminder_prompt"
        const val KEY_BATTERY_OFFERED = "battery_offered"
    }
}
