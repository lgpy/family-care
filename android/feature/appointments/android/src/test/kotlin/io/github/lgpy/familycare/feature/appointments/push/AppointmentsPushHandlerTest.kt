package io.github.lgpy.familycare.feature.appointments.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The appointments push claim (D29): exactly `appointment_changed`, so the core
 * router hands it that type and no other feature has to know appointments exist.
 */
class AppointmentsPushHandlerTest {

    private val handler = AppointmentsPushHandler()

    @Test
    fun `the handler claims only the appointment change type`() {
        assertEquals(setOf("appointment_changed"), handler.types)
    }

    @Test
    fun `the change push lands on its own channel`() {
        assertEquals("appointment_updates", AppointmentPush.CHANNEL_ID)
    }

    @Test
    fun `no other type is claimed`() {
        assertNull(handler.types.firstOrNull { it == "escalation" || it == "clear" })
    }

    @Test
    fun `the handler exists for the router`() {
        assertNotNull(handler.types)
    }
}
