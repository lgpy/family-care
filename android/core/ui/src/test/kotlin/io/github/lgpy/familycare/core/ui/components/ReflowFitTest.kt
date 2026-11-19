package io.github.lgpy.familycare.core.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reflow decision is the one piece of the design system that must be
 * provably correct without a device: it decides whether a dose slot's button
 * shares the patient's line or drops below it.
 */
class ReflowFitTest {

    @Test
    fun `content and action share a line when the sum fits exactly`() {
        assertTrue(reflowFits(availableWidth = 300, contentWidth = 200, actionWidth = 88, spacing = 12))
    }

    @Test
    fun `one pixel too wide stacks`() {
        assertFalse(reflowFits(availableWidth = 300, contentWidth = 201, actionWidth = 88, spacing = 12))
    }

    @Test
    fun `spacing counts towards the fit`() {
        assertFalse(reflowFits(availableWidth = 300, contentWidth = 200, actionWidth = 88, spacing = 13))
        assertTrue(reflowFits(availableWidth = 300, contentWidth = 200, actionWidth = 88, spacing = 12))
    }

    @Test
    fun `an unbounded width always fits`() {
        assertTrue(
            reflowFits(
                availableWidth = Int.MAX_VALUE,
                contentWidth = 10_000,
                actionWidth = 10_000,
                spacing = 12,
            ),
        )
    }

    @Test
    fun `a large font scale widens the content and stacks`() {
        // The same 240 dp line: at 100% the content measures 150 px, at 200% it
        // measures 320 px. No font scale is consulted, only the measurements.
        val available = 260
        val action = 80
        val spacing = 12
        assertTrue(reflowFits(available, contentWidth = 150, actionWidth = action, spacing = spacing))
        assertFalse(reflowFits(available, contentWidth = 320, actionWidth = action, spacing = spacing))
    }

    @Test
    fun `an empty action still pays for the spacing`() {
        // An empty action is handled before the decision (content takes the
        // whole width), so this only documents the pure function's arithmetic.
        assertTrue(reflowFits(availableWidth = 200, contentWidth = 188, actionWidth = 0, spacing = 12))
        assertFalse(reflowFits(availableWidth = 200, contentWidth = 189, actionWidth = 0, spacing = 12))
    }

    @Test
    fun `nonsensical measurements never fit`() {
        assertFalse(reflowFits(availableWidth = 0, contentWidth = 10, actionWidth = 10, spacing = 0))
        assertFalse(reflowFits(availableWidth = -1, contentWidth = 10, actionWidth = 10, spacing = 0))
        assertFalse(reflowFits(availableWidth = 100, contentWidth = -1, actionWidth = 10, spacing = 0))
        assertFalse(reflowFits(availableWidth = 100, contentWidth = 10, actionWidth = -1, spacing = 0))
        assertFalse(reflowFits(availableWidth = 100, contentWidth = 10, actionWidth = 10, spacing = -1))
    }
}
