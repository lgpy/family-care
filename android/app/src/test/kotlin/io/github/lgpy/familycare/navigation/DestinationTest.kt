package io.github.lgpy.familycare.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationTest {

    @Test
    fun todayIsTheStartDestination() {
        assertEquals("today", Destination.Today.route)
    }

    @Test
    fun theBottomBarIsTodayPatientsShoppingAndMore() {
        assertEquals(
            listOf(Destination.Today, Destination.Patients, Destination.Shopping, Destination.More),
            Destination.topLevel,
        )
        assertEquals("patients", Destination.Patients.route)
        assertEquals("shopping", Destination.Shopping.route)
    }

    @Test
    fun settingsBecameMore() {
        assertEquals("more", Destination.More.route)
        assertTrue(Destination.entries.none { it.route == "settings" })
    }

    @Test
    fun moreOpensTheCircleAndTheUsersName() {
        assertEquals("more/family-circle", Destination.FamilyCircle.route)
        assertEquals("more/your-name", Destination.YourName.route)
        assertEquals("about", Destination.About.route)
    }

    @Test
    fun thePatientEditRouteCarriesThePatientId() {
        assertEquals("patients/{patientId}", Destination.PatientEdit.route)
        assertEquals("patients/new", Destination.PatientNew.route)
        assertEquals("patientId", PATIENT_ID_ARG)
        assertEquals("patients/patient-1", Destination.patientEditRoute("patient-1"))
    }

    @Test
    fun routesAreNonBlankAndUnique() {
        val routes = Destination.entries.map { it.route }

        assertTrue(routes.all { it.isNotBlank() })
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun everyTopLevelDestinationIsPartOfTheShell() {
        assertTrue(Destination.topLevel.all { it in Destination.entries })
        assertEquals(4, Destination.topLevel.size)
    }
}
