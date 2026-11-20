package io.github.lgpy.familycare.core.domain.medication

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The drawn pill look, case by case: the `pill_*` tokens from the database, the
 * degradation rules an older phone needs, and the one structural rule the
 * server also enforces (a second colour only on a capsule).
 *
 * The tokens are the mockup's (`docs/ux/mockups/screens-v2.html`, `pill()`); if
 * one of these tests changes, the palette in the mockup changed with it.
 */
class PillLookTest {

    // --- fromServer: known rows ---------------------------------------------

    @Test
    fun `a full row becomes the identical look`() {
        val look = PillLookRules.fromServer(
            shape = "oblong",
            color = "white",
            color2 = null,
            score = true,
            size = "large",
        )

        assertEquals(
            PillLook(
                shape = PillShape.OBLONG,
                color = PillColor.WHITE,
                color2 = null,
                score = true,
                size = PillSize.LARGE,
            ),
            look,
        )
    }

    @Test
    fun `a two-tone capsule keeps its second colour`() {
        val look = PillLookRules.fromServer(
            shape = "capsule",
            color = "purple",
            color2 = "pink",
            score = false,
            size = "medium",
        )

        assertEquals(PillColor.PURPLE, look?.color)
        assertEquals(PillColor.PINK, look?.color2)
    }

    @Test
    fun `every documented shape and colour token parses`() {
        assertEquals(
            listOf("round", "oval", "oblong", "capsule", "diamond", "triangle", "pentagon", "drops"),
            PillShape.entries.map { it.serverValue },
        )
        assertEquals(
            listOf(
                "white",
                "cream",
                "yellow",
                "orange",
                "pink",
                "red",
                "blue",
                "navy",
                "green",
                "brown",
                "purple",
                "grey",
            ),
            PillColor.tokens,
        )
        PillShape.entries.forEach { assertEquals(it, PillShape.fromServer(it.serverValue)) }
        PillColor.entries.forEach { assertEquals(it, PillColor.fromServer(it.serverValue)) }
        PillSize.entries.forEach { assertEquals(it, PillSize.fromServer(it.serverValue)) }
    }

    @Test
    fun `sizes carry the drawing scale`() {
        assertEquals(0.8f, PillSize.SMALL.scale)
        assertEquals(1f, PillSize.MEDIUM.scale)
        assertEquals(1.1f, PillSize.LARGE.scale)
    }

    // --- fromServer: unknown values never crash -----------------------------

    @Test
    fun `no shape or an unknown shape means no drawn pill`() {
        assertNull(PillLookRules.fromServer(null, "white", null, false, "medium"))
        assertNull(PillLookRules.fromServer("lollipop", "white", null, false, "medium"))
        assertNull(PillLookRules.fromServer("", "white", null, false, "medium"))
    }

    @Test
    fun `an unknown colour falls back to no colour, not to a missing medication`() {
        val look = PillLookRules.fromServer("round", "chartreuse", "pink", false, "medium")

        assertEquals(PillShape.ROUND, look?.shape)
        assertNull(look?.color)
        // The capsule rule still applies to the second colour.
        assertNull(look?.color2)
    }

    @Test
    fun `an unknown or missing size becomes medium`() {
        assertEquals(
            PillSize.MEDIUM,
            PillLookRules.fromServer("round", null, null, false, "enormous")?.size,
        )
        assertEquals(
            PillSize.MEDIUM,
            PillLookRules.fromServer("round", null, null, false, null)?.size,
        )
    }

    @Test
    fun `the score flag is passed through`() {
        assertFalse(PillLookRules.fromServer("round", null, null, false, null)!!.score)
        assertTrue(PillLookRules.fromServer("round", null, null, true, null)!!.score)
    }

    @Test
    fun `a second colour on a non-capsule is dropped`() {
        listOf("round", "oval", "oblong", "diamond", "triangle", "pentagon", "drops").forEach { shape ->
            assertNull(
                PillLookRules.fromServer(shape, "white", "pink", false, null)?.color2,
                "expected no second colour on $shape",
            )
        }
    }

    // --- normalize / validate -----------------------------------------------

    @Test
    fun `normalize drops a second colour from every shape but a capsule`() {
        val round = PillLook(shape = PillShape.ROUND, color = PillColor.WHITE, color2 = PillColor.PINK)
        assertEquals(PillLook(shape = PillShape.ROUND, color = PillColor.WHITE), PillLookRules.normalize(round))

        val capsule = PillLook(
            shape = PillShape.CAPSULE,
            color = PillColor.PURPLE,
            color2 = PillColor.PINK,
            score = true,
            size = PillSize.SMALL,
        )
        assertEquals(capsule, PillLookRules.normalize(capsule))
    }

    @Test
    fun `validate reports only a second colour on a non-capsule`() {
        assertEquals(
            setOf(PillLookField.PILL_LOOK_COLOR2),
            PillLookRules.validate(
                PillLook(shape = PillShape.OVAL, color = PillColor.YELLOW, color2 = PillColor.BLUE),
            ),
        )
        assertEquals(
            emptySet<PillLookField>(),
            PillLookRules.validate(PillLook(shape = PillShape.CAPSULE, color2 = PillColor.BLUE)),
        )
        assertEquals(emptySet<PillLookField>(), PillLookRules.validate(PillLook(shape = PillShape.DROPS)))
    }
}
