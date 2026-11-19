package io.github.lgpy.familycare.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import kotlin.math.min

/**
 * How far a label in fixed-width chrome may grow with the system font: the
 * bottom bar and any other label whose width is decided by the screen, not by
 * its text.
 */
const val FixedChromeMaxFontScale: Float = 1.3f

/**
 * [base] with its effective size capped at [maxScale] times the text as set,
 * however large the user's system font is.
 *
 * Text is sp and must scale with the system font: this helper is **only** for
 * fixed-width chrome such as a bottom-navigation label, where the row has no
 * spare width and a 200% label would break mid-word ("Patie/nts"). Content
 * text (medicine names, allergies, times, patient names) must never be capped;
 * it reflows and grows instead (docs/ux/design-system.md §3).
 *
 * The arithmetic is the point: a sp value is multiplied by the system
 * `fontScale` when drawn, so returning `base * min(fontScale, maxScale) /
 * fontScale` makes the drawn size `base * min(fontScale, maxScale)`.
 */
@Composable
fun rememberCappedFontSize(
    base: TextUnit,
    maxScale: Float = FixedChromeMaxFontScale,
): TextUnit {
    val fontScale = LocalDensity.current.fontScale
    return remember(base, maxScale, fontScale) {
        if (!base.isSpecified || fontScale <= 0f) {
            base
        } else {
            base * (min(fontScale, maxScale) / fontScale)
        }
    }
}
