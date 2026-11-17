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
    fun settingsIsPartOfTheShell() {
        assertEquals("settings", Destination.Settings.route)
    }

    @Test
    fun theM1SyncProbeIsReachableFromSettings() {
        assertEquals("sync-test", Destination.SyncTest.route)
    }

    @Test
    fun routesAreNonBlankAndUnique() {
        val routes = Destination.entries.map { it.route }

        assertTrue(routes.all { it.isNotBlank() })
        assertEquals(routes.size, routes.toSet().size)
    }
}
