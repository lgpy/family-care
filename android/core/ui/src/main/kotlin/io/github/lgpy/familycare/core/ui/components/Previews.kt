package io.github.lgpy.familycare.core.ui.components

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * The previews every component ships with: the phone width from the mockup
 * (384 dp, Galaxy A class) at 100%, 150% and 200% system font size, plus dark
 * mode at 100%.
 *
 * The layout must not change for a font size: it reflows only when measured
 * content stops fitting. These previews are how a change is checked at all
 * three sizes (docs/ux/design-system.md §5).
 */
@Preview(name = "100%", widthDp = 384, fontScale = 1f, showBackground = true)
@Preview(name = "150%", widthDp = 384, fontScale = 1.5f, showBackground = true)
@Preview(name = "200%", widthDp = 384, fontScale = 2f, showBackground = true)
@Preview(
    name = "Dark 100%",
    widthDp = 384,
    fontScale = 1f,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class ScalePreviews
