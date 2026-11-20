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
    fun theBottomBarIsTodayPatientsAgendaShoppingAndMore() {
        assertEquals(
            listOf(
                Destination.Today,
                Destination.Patients,
                Destination.Agenda,
                Destination.Shopping,
                Destination.More,
            ),
            Destination.topLevel,
        )
        assertEquals("patients", Destination.Patients.route)
        assertEquals("agenda", Destination.Agenda.route)
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
        assertEquals("more/family-circle/invite", Destination.FamilyCircleInvite.route)
        assertEquals("more/who-reminded", Destination.WhoGetsReminded.route)
        assertEquals("more/your-name", Destination.YourName.route)
        assertEquals("about", Destination.About.route)
    }

    @Test
    fun thePatientProfileAndEditRoutesCarryThePatientId() {
        assertEquals("patients/{patientId}", Destination.PatientProfile.route)
        assertEquals("patients/{patientId}/edit", Destination.PatientEdit.route)
        assertEquals("patients/new", Destination.PatientNew.route)
        assertEquals("patientId", PATIENT_ID_ARG)
        assertEquals("patients/patient-1", Destination.patientProfileRoute("patient-1"))
        assertEquals("patients/patient-1/edit", Destination.patientEditRoute("patient-1"))
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
        assertEquals(5, Destination.topLevel.size)
    }
}
