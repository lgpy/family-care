package io.github.lgpy.familycare

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockGate
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.feature.medications.reminders.ReminderContract
import io.github.lgpy.familycare.navigation.FamilyCareApp
import io.github.lgpy.familycare.ui.AuthGate
import io.github.lgpy.familycare.ui.OrderedPatientColors
import io.github.lgpy.familycare.ui.circle.CircleGate
import io.github.lgpy.familycare.ui.profile.ProfileLanguageSync
import org.koin.compose.koinInject

/**
 * FragmentActivity (not just ComponentActivity) because the mandatory app lock
 * uses AndroidX BiometricPrompt, which needs a FragmentActivity. The platform
 * LocaleManager handles per-app language, so AppCompat is not required.
 */
class MainActivity : FragmentActivity() {

    /**
     * A route from a dose notification's content intent (M4), consumed once by
     * the navigation graph after the lock and gates have been passed.
     */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute = intent?.getStringExtra(ReminderContract.EXTRA_ROUTE)

        // Health data must never show in the Recents thumbnail. Deliberately
        // not FLAG_SECURE: screenshots inside the app stay allowed.
        setRecentsScreenshotEnabled(false)

        setContent {
            FamilyCareTheme {
                // D32: one order-based patient palette for the whole app, all
                // screens and every feature module, archived patients included.
                OrderedPatientColors {
                    val appLockController = koinInject<AppLockController>()
                    AppLockGate(
                        controller = appLockController,
                        activity = this@MainActivity,
                    ) {
                        // D12: the app lock comes first, then the Supabase session,
                        // then the care-circle gate (M2). A signed-out user, or one
                        // who belongs to no family, never sees app content.
                        AuthGate(activity = this@MainActivity) {
                            // D8: a new phone follows the account's language until
                            // the user chooses one here.
                            ProfileLanguageSync()
                            CircleGate {
                                FamilyCareApp(
                                    initialRoute = pendingRoute,
                                    onInitialRouteOpened = { pendingRoute = null },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(ReminderContract.EXTRA_ROUTE)
    }
}
