package io.github.lgpy.familycare

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.feature.medications.reminders.ReminderContract
import io.github.lgpy.familycare.navigation.FamilyCareApp
import io.github.lgpy.familycare.ui.AuthGate
import io.github.lgpy.familycare.ui.OrderedPatientColors
import io.github.lgpy.familycare.ui.circle.CircleGate
import io.github.lgpy.familycare.ui.profile.ProfileLanguageSync

/**
 * Single-activity Compose host.
 */
class MainActivity : ComponentActivity() {

    /**
     * A route from a dose notification's content intent (M4), consumed once by
     * the navigation graph after the gates have been passed.
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
                    // D12: the Supabase session comes first, then the care-circle
                    // gate (M2). A signed-out user, or one who belongs to no
                    // family, never sees app content.
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(ReminderContract.EXTRA_ROUTE)
    }
}
