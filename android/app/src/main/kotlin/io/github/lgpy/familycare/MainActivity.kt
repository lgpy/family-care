package io.github.lgpy.familycare

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockGate
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.navigation.FamilyCareApp
import io.github.lgpy.familycare.ui.AuthGate
import io.github.lgpy.familycare.ui.circle.CircleGate
import io.github.lgpy.familycare.ui.profile.ProfileLanguageSync
import org.koin.compose.koinInject

/**
 * FragmentActivity (not just ComponentActivity) because the mandatory app lock
 * uses AndroidX BiometricPrompt, which needs a FragmentActivity. The platform
 * LocaleManager handles per-app language, so AppCompat is not required.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Health data must never show in the Recents thumbnail. Deliberately
        // not FLAG_SECURE: screenshots inside the app stay allowed.
        setRecentsScreenshotEnabled(false)

        setContent {
            FamilyCareTheme {
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
                            FamilyCareApp()
                        }
                    }
                }
            }
        }
    }
}
