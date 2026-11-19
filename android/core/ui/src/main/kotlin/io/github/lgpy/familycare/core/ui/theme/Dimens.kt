package io.github.lgpy.familycare.core.ui.theme

import androidx.compose.ui.unit.dp

/*
 * Size tokens (docs/ux/design-system.md §3).
 *
 * Text is sp, everything else is dp. These are the shared dp constants; a
 * component that needs a one-off size keeps it local with a comment.
 */
internal object FamilyCareDimens {

    /** Touch target minimum; M3 already enforces this via minimumInteractiveComponentSize. */
    val MinTouchTarget = 48.dp

    /** Cards and dose slots. */
    val CardRadius = 20.dp

    /** Inside an attention card. */
    val CardPadding = 16.dp

    /** Inside a dose slot: 12 dp top/bottom, 14 dp left/right in the mockup. */
    val SlotVerticalPadding = 12.dp
    val SlotHorizontalPadding = 14.dp

    /** Avatars are a fixed dp circle; the initial inside never scales with the system font. */
    val AvatarSize = 32.dp

    /** The stock meter's bar. */
    val StockBarHeight = 8.dp

    /** The narrowest the stock bar may get before the label moves under it. */
    val MinStockBarWidth = 96.dp
}
