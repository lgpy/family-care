package io.github.lgpy.familycare.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * The size an icon needs to sit *inside* a line of text, at the user's font
 * scale (design-system §6).
 *
 * A 16 dp meta icon is right at 100% and a dot beside the same sentence at
 * 200%: the text doubles and the icon does not. This measures the text
 * itself — [style]'s `fontSize` through [LocalDensity], so `fontScale` is
 * already applied — and returns a slightly larger icon that is clamped to
 * [min]..[max]: 16 dp at 100%, about 32 dp at 200% for body text.
 *
 * Only icons that share a line (or a chip/tag) with text use this. Chrome
 * icons — top bar, bottom bar, list tiles, the 36 dp attention tile — stay a
 * fixed 24 dp, because they are not part of a sentence.
 *
 * @param style the [TextStyle] of the text the icon sits beside.
 * @param factor how much taller than the font the icon is (1.15 reads as
 *   balanced for body text).
 * @param max the ceiling, so a headline-sized line never gets a 60 dp glyph.
 */
@Composable
fun inlineIconSize(
    style: TextStyle,
    factor: Float = 1.15f,
    max: Dp = 32.dp,
): Dp {
    val fontSize = style.fontSize
    if (fontSize == TextUnit.Unspecified) return 16.dp
    return with(LocalDensity.current) {
        (fontSize.toDp() * factor).coerceIn(16.dp, max)
    }
}
