package io.github.lgpy.familycare.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes
import io.github.lgpy.familycare.feature.appointments.agenda.AgendaScreen
import io.github.lgpy.familycare.feature.appointments.detail.AppointmentDetailScreen
import io.github.lgpy.familycare.feature.appointments.form.AppointmentFormScreen
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.detail.MedicationDetailScreen
import io.github.lgpy.familycare.feature.medications.doses.DoseSlotScreen
import io.github.lgpy.familycare.feature.medications.form.MedicationFormScreen
import io.github.lgpy.familycare.feature.medications.list.PatientMedicationsScreen
import io.github.lgpy.familycare.feature.medications.regimen.ChangeRegimenScreen
import io.github.lgpy.familycare.feature.medications.shopping.ShoppingScreen
import io.github.lgpy.familycare.feature.patientinfo.PatientInfoRoutes
import io.github.lgpy.familycare.feature.patientinfo.edit.PatientInfoEditScreen
import io.github.lgpy.familycare.ui.AboutScreen
import io.github.lgpy.familycare.ui.MoreScreen
import io.github.lgpy.familycare.ui.TodayScreen
import io.github.lgpy.familycare.ui.circle.FamilyCircleScreen
import io.github.lgpy.familycare.ui.patients.PatientFormScreen
import io.github.lgpy.familycare.ui.patients.PatientProfileScreen
import io.github.lgpy.familycare.ui.patients.PatientsScreen
import io.github.lgpy.familycare.ui.profile.YourNameScreen
import io.github.lgpy.familycare.ui.reminders.RemindersScreen
import io.github.lgpy.familycare.ui.sync.UploadRejectionBanner

/**
 * The app shell once the user belongs to a circle: a bottom bar with Today,
 * Patients, Agenda, Shopping and More, and the screens those open.
 *
 * The top bar is the persistent message about a change the server refused and
 * the queue discarded (see `UploadRejectionBanner`); it is empty while there is
 * no such change, including during offline or transient sync trouble.
 *
 * The Agenda and the appointments screens belong to the appointments module: the
 * shell only composes them and uses [AppointmentsRoutes] for their routes.
 * The care-circle gate lives outside this composable (see `CircleGate`), so
 * removing the last membership takes the user back there without the shell
 * needing to know.
 */
@Composable
fun FamilyCareApp(
    modifier: Modifier = Modifier,
    initialRoute: String? = null,
    onInitialRouteOpened: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Destination.topLevel.any { it.route == currentRoute }

    // A dose notification opens its dose-slot screen, but only once the lock,
    // sign-in and circle gates above this composable have let the user in.
    LaunchedEffect(initialRoute) {
        if (!initialRoute.isNullOrBlank() && initialRoute != currentRoute) {
            navController.navigate(initialRoute) { launchSingleTop = true }
            onInitialRouteOpened()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { UploadRejectionBanner() },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Destination.topLevel.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon(),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(text = stringResource(destination.labelRes())) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Today.route) {
                TodayScreen(
                    onOpenRoute = { route -> navController.navigate(route) },
                )
            }
            composable(Destination.Patients.route) {
                PatientsScreen(
                    onAddPatient = { navController.navigate(Destination.PatientNew.route) },
                    onOpenPatient = { patientId ->
                        navController.navigate(Destination.patientProfileRoute(patientId))
                    },
                )
            }
            composable(Destination.Shopping.route) {
                ShoppingScreen(
                    onOpenMedication = { patientId, medicationId ->
                        navController.navigate(
                            MedicationsRoutes.medicationDetail(patientId, medicationId),
                        )
                    },
                )
            }
            composable(Destination.Agenda.route) {
                AgendaScreen(
                    onOpenAppointment = { appointmentId ->
                        navController.navigate(AppointmentsRoutes.appointmentDetail(appointmentId))
                    },
                    onNewAppointment = {
                        navController.navigate(AppointmentsRoutes.newAppointment())
                    },
                )
            }
            composable(Destination.PatientNew.route) {
                PatientFormScreen(
                    patientId = null,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Destination.PatientProfile.route,
                arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                PatientProfileScreen(
                    patientId = entry.arguments?.getString(PATIENT_ID_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onEdit = { patientId ->
                        navController.navigate(Destination.patientEditRoute(patientId))
                    },
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
            composable(
                route = Destination.PatientEdit.route,
                arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                PatientFormScreen(
                    patientId = entry.arguments?.getString(PATIENT_ID_ARG),
                    onBack = { navController.popBackStack() },
                )
            }
            // Patient info's edit form, contributed by the patient-info module:
            // the profile's Info tab opens it through onNavigate (D29).
            composable(
                route = PatientInfoRoutes.EDIT,
                arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                PatientInfoEditScreen(
                    patientId = entry.arguments?.getString(PATIENT_ID_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Destination.More.route) {
                MoreScreen(
                    onOpenFamilyCircle = {
                        navController.navigate(Destination.FamilyCircle.route)
                    },
                    onOpenReminders = {
                        navController.navigate(Destination.Reminders.route)
                    },
                    onOpenYourName = { navController.navigate(Destination.YourName.route) },
                    onOpenAbout = { navController.navigate(Destination.About.route) },
                )
            }
            composable(Destination.FamilyCircle.route) {
                FamilyCircleScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.Reminders.route) {
                RemindersScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.YourName.route) {
                YourNameScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.About.route) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Destination.PatientMedications.route,
                arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                val patientId = entry.arguments?.getString(PATIENT_ID_ARG).orEmpty()
                PatientMedicationsScreen(
                    patientId = patientId,
                    onBack = { navController.popBackStack() },
                    onAdd = {
                        navController.navigate(MedicationsRoutes.newMedication(patientId))
                    },
                    onOpenMedication = { medicationId ->
                        navController.navigate(
                            MedicationsRoutes.medicationDetail(patientId, medicationId),
                        )
                    },
                )
            }
            composable(
                route = Destination.MedicationNew.route,
                arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                MedicationFormScreen(
                    patientId = entry.arguments?.getString(PATIENT_ID_ARG).orEmpty(),
                    medicationId = null,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Destination.MedicationDetail.route,
                arguments = listOf(
                    navArgument(PATIENT_ID_ARG) { type = NavType.StringType },
                    navArgument(MEDICATION_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val patientId = entry.arguments?.getString(PATIENT_ID_ARG).orEmpty()
                val medicationId = entry.arguments?.getString(MEDICATION_ID_ARG).orEmpty()
                MedicationDetailScreen(
                    medicationId = medicationId,
                    onBack = { navController.popBackStack() },
                    onEdit = {
                        navController.navigate(
                            MedicationsRoutes.editMedication(patientId, medicationId),
                        )
                    },
                    onChangeSchedule = {
                        navController.navigate(
                            MedicationsRoutes.changeRegimen(patientId, medicationId),
                        )
                    },
                )
            }
            composable(
                route = Destination.MedicationEdit.route,
                arguments = listOf(
                    navArgument(PATIENT_ID_ARG) { type = NavType.StringType },
                    navArgument(MEDICATION_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                MedicationFormScreen(
                    patientId = entry.arguments?.getString(PATIENT_ID_ARG).orEmpty(),
                    medicationId = entry.arguments?.getString(MEDICATION_ID_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Destination.MedicationSchedule.route,
                arguments = listOf(
                    navArgument(PATIENT_ID_ARG) { type = NavType.StringType },
                    navArgument(MEDICATION_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                ChangeRegimenScreen(
                    medicationId = entry.arguments?.getString(MEDICATION_ID_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Destination.DoseSlot.route,
                arguments = listOf(
                    navArgument(PATIENT_ID_ARG) { type = NavType.StringType },
                    navArgument(SCHEDULED_LOCAL_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val scheduledLocal = MedicationsRoutes.decodeScheduledLocal(
                    entry.arguments?.getString(SCHEDULED_LOCAL_ARG),
                )
                if (scheduledLocal == null) {
                    navController.popBackStack()
                } else {
                    DoseSlotScreen(
                        patientId = entry.arguments?.getString(PATIENT_ID_ARG).orEmpty(),
                        scheduledLocal = scheduledLocal,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(
                route = AppointmentsRoutes.APPOINTMENT_NEW,
                arguments = listOf(
                    navArgument(AppointmentsRoutes.PATIENT_ID_ARG) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                AppointmentFormScreen(
                    patientId = entry.arguments
                        ?.getString(AppointmentsRoutes.PATIENT_ID_ARG)
                        ?.takeIf { it.isNotBlank() },
                    appointmentId = null,
                    followUpOf = null,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(
                route = AppointmentsRoutes.APPOINTMENT_DETAIL,
                arguments = listOf(
                    navArgument(AppointmentsRoutes.APPOINTMENT_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                AppointmentDetailScreen(
                    appointmentId = entry.arguments
                        ?.getString(AppointmentsRoutes.APPOINTMENT_ID_ARG)
                        .orEmpty(),
                    onBack = { navController.popBackStack() },
                    onEdit = { appointmentId ->
                        navController.navigate(AppointmentsRoutes.editAppointment(appointmentId))
                    },
                    onFollowUp = { appointmentId ->
                        navController.navigate(AppointmentsRoutes.followUp(appointmentId))
                    },
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable(
                route = AppointmentsRoutes.APPOINTMENT_EDIT,
                arguments = listOf(
                    navArgument(AppointmentsRoutes.APPOINTMENT_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                AppointmentFormScreen(
                    patientId = null,
                    appointmentId = entry.arguments
                        ?.getString(AppointmentsRoutes.APPOINTMENT_ID_ARG)
                        .orEmpty(),
                    followUpOf = null,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(
                route = AppointmentsRoutes.APPOINTMENT_FOLLOW_UP,
                arguments = listOf(
                    navArgument(AppointmentsRoutes.APPOINTMENT_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                AppointmentFormScreen(
                    patientId = null,
                    appointmentId = null,
                    followUpOf = entry.arguments
                        ?.getString(AppointmentsRoutes.APPOINTMENT_ID_ARG)
                        .orEmpty(),
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Switches bottom-bar tabs without stacking them up. */
private fun NavHostController.navigateToTopLevel(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun Destination.icon(): ImageVector = when (this) {
    Destination.Today -> Icons.Filled.Home
    Destination.Patients -> Icons.Filled.Person
    Destination.Agenda -> Icons.Filled.DateRange
    Destination.Shopping -> Icons.Filled.ShoppingCart
    Destination.More -> Icons.Filled.MoreVert
    else -> Icons.Filled.MoreVert
}

@StringRes
private fun Destination.labelRes(): Int = when (this) {
    Destination.Today -> R.string.nav_today
    Destination.Patients -> R.string.nav_patients
    Destination.Agenda -> R.string.nav_agenda
    Destination.Shopping -> R.string.nav_shopping
    Destination.More -> R.string.nav_more
    else -> R.string.nav_more
}
