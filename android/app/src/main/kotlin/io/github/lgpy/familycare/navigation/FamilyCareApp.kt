package io.github.lgpy.familycare.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.lgpy.familycare.ui.AboutScreen
import io.github.lgpy.familycare.ui.SettingsScreen
import io.github.lgpy.familycare.ui.SyncTestScreen
import io.github.lgpy.familycare.ui.TodayScreen

@Composable
fun FamilyCareApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Destination.Today.route,
        modifier = modifier,
    ) {
        composable(Destination.Today.route) {
            TodayScreen(
                onOpenSettings = { navController.navigate(Destination.Settings.route) },
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(
                onOpenAbout = { navController.navigate(Destination.About.route) },
                onOpenSyncTest = { navController.navigate(Destination.SyncTest.route) },
            )
        }
        composable(Destination.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        // M1 probe; delete in M2 together with core.sync_probe.
        composable(Destination.SyncTest.route) {
            SyncTestScreen(onBack = { navController.popBackStack() })
        }
    }
}
