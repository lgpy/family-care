package io.github.lgpy.familycare.ui.patients

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile's tab row decides fixed vs scrollable by measuring the labels
 * against their equal share of the width — never by `fontScale`.
 */
class ProfileTabFitTest {

    @Test
    fun `four labels that fit their quarters keep the fixed row`() {
        assertTrue(
            fixedTabsFit(
                availableWidthPx = 1000,
                labelWidthsPx = listOf(200, 230, 180, 150),
                tabPaddingPx = 16,
            ),
        )
    }

    @Test
    fun `one label wider than its quarter falls back to scrolling`() {
        assertFalse(
            fixedTabsFit(
                availableWidthPx = 1000,
                labelWidthsPx = listOf(200, 236, 180, 150),
                tabPaddingPx = 16,
            ),
        )
    }

    @Test
    fun `the same labels at a larger font no longer fit`() {
        val normal = listOf(200, 230, 180, 150)
        val doubled = normal.map { it * 2 }

        assertTrue(fixedTabsFit(availableWidthPx = 1000, labelWidthsPx = normal, tabPaddingPx = 16))
        assertFalse(fixedTabsFit(availableWidthPx = 1000, labelWidthsPx = doubled, tabPaddingPx = 16))
    }

    @Test
    fun `nothing to fit is not a reason to scroll`() {
        assertTrue(fixedTabsFit(availableWidthPx = 1000, labelWidthsPx = emptyList(), tabPaddingPx = 16))
    }
}
