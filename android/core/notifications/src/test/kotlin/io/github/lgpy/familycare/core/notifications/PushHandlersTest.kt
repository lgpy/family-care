package io.github.lgpy.familycare.core.notifications

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push type router (M6; D29): a claimed type reaches its handler, an
 * unknown or missing type is ignored, and a type claimed twice is rejected when
 * the registry is built (at start-up).
 */
class PushHandlersTest {

    private class FakeHandler(
        override val types: Set<String>,
    ) : PushHandler {

        val handles = mutableListOf<Map<String, String>>()

        override fun handle(
            context: Context,
            data: Map<String, String>,
            notificationTag: String?,
        ) {
            handles += data
        }
    }

    @Test
    fun `a claimed type is dispatched to its handler`() {
        val escalation = FakeHandler(setOf("escalation"))
        val clear = FakeHandler(setOf("clear"))
        val registry = PushHandlers(listOf(escalation, clear))

        var delivered: PushHandler? = null
        registry.dispatch(mapOf("type" to "clear")) { delivered = it }

        assertSame(clear, delivered)
        assertTrue(escalation.handles.isEmpty())
    }

    @Test
    fun `an unknown type is ignored`() {
        val registry = PushHandlers(listOf(FakeHandler(setOf("escalation"))))

        var delivered: PushHandler? = null
        registry.dispatch(mapOf("type" to "appointment_changed")) { delivered = it }

        assertNull(delivered)
    }

    @Test
    fun `a missing type is ignored`() {
        val registry = PushHandlers(listOf(FakeHandler(setOf("escalation"))))

        var delivered: PushHandler? = null
        registry.dispatch(mapOf("patient_id" to "patient-1")) { delivered = it }

        assertNull(delivered)
    }

    @Test
    fun `a blank type is ignored`() {
        val registry = PushHandlers(listOf(FakeHandler(setOf("escalation"))))

        assertNull(registry.handlerFor(""))
        assertNull(registry.handlerFor(null))
    }

    @Test
    fun `a type claimed by two handlers is rejected when the registry is built`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PushHandlers(
                listOf(
                    FakeHandler(setOf("escalation")),
                    FakeHandler(setOf("clear", "escalation")),
                ),
            )
        }

        assertEquals(
            "Two push handlers claim the same type: [escalation]",
            error.message,
        )
    }
}
