package io.github.lgpy.familycare.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.lgpy.familycare.ui.AboutScreen
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
                onOpenAbout = { navController.navigate(Destination.About.route) },
            )
        }
        composable(Destination.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
