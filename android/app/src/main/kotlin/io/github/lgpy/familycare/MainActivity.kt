package io.github.lgpy.familycare

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockGate
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.navigation.FamilyCareApp
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
                    FamilyCareApp()
                }
            }
        }
    }
}
