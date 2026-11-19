package io.github.lgpy.familycare.core.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.lgpy.familycare.core.domain.medication.PillLook

/**
 * The pill pictures of a reminder notification, as a bitmap (docs/ux/mockups/
 * screens-v2.html `const notif`).
 *
 * [PillImage] draws on a Compose canvas; a notification's large icon needs an
 * `android.graphics.Bitmap`, and no composition is available where the
 * notification is built. Both paths share [drawPill] so the notification shows
 * exactly the picture the app shows.
 */

/** At most six pills fit in the notification's square icon. */
const val PillBitmapMaxPills: Int = 6

/** The icon holds at most three pills per row; more wrap onto a second row. */
const val PillBitmapColumns: Int = 3

/** The pill's share of its cell, matching the mockup's airy spacing. */
internal const val PillBitmapCellFraction: Float = 0.8f

/**
 * Where one pill sits in the square bitmap: [left] and [top] are the pill's
 * box origin and [size] is the box's side, all in pixels.
 */
data class PillBitmapSlot(
    val left: Float,
    val top: Float,
    val size: Float,
) {

    /** The box's centre, which is where [drawPill] draws. */
    val centre: Offset get() = Offset(left + size / 2f, top + size / 2f)
}

/**
 * The layout of [count] pills in a [sizePx] square: one row of up to three,
 * and a second row for the rest (the brief allows three across, two rows).
 *
 * This is a pure function so the arrangement is unit-tested on the JVM without
 * Android. Every row is centred horizontally, so a short second row sits under
 * the middle of the first, and every pill gets the same size (the largest
 * square that fits the widest row and the row height) so a two-row icon does
 * not draw the last pill bigger.
 *
 * Returns an empty list for a non-positive count or size; [count] above
 * [PillBitmapMaxPills] is capped, because a notification icon has no room for
 * more.
 */
fun pillBitmapSlots(count: Int, sizePx: Int): List<PillBitmapSlot> {
    if (count <= 0 || sizePx <= 0) return emptyList()
    val pills = count.coerceAtMost(PillBitmapMaxPills)
    val rows = (pills + PillBitmapColumns - 1) / PillBitmapColumns
    val rowHeight = sizePx.toFloat() / rows
    val columns = minOf(pills, PillBitmapColumns)
    val pillSize = minOf(sizePx.toFloat() / columns, rowHeight) * PillBitmapCellFraction

    val slots = ArrayList<PillBitmapSlot>(pills)
    for (row in 0 until rows) {
        val inRow = minOf(PillBitmapColumns, pills - row * PillBitmapColumns)
        val cellWidth = sizePx.toFloat() / inRow
        for (column in 0 until inRow) {
            val centreX = column * cellWidth + cellWidth / 2f
            val centreY = row * rowHeight + rowHeight / 2f
            slots += PillBitmapSlot(
                left = centreX - pillSize / 2f,
                top = centreY - pillSize / 2f,
                size = pillSize,
            )
        }
    }
    return slots
}

/**
 * Draws [looks] side by side into a transparent [sizePx] square and returns it
 * as a notification large icon, or null when there is nothing to draw.
 *
 * Uses [CanvasDrawScope] over an [androidx.compose.ui.graphics.ImageBitmap], so
 * it needs no composition and no main thread. The bitmap is ARGB_8888 and
 * starts fully transparent: the notification supplies its own background.
 */
fun renderPillsBitmap(looks: List<PillLook>, sizePx: Int): Bitmap? {
    if (looks.isEmpty() || sizePx <= 0) return null

    val bitmap = ImageBitmap(sizePx, sizePx, ImageBitmapConfig.Argb8888)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(sizePx.toFloat(), sizePx.toFloat()),
    ) {
        pillBitmapSlots(looks.size, sizePx).forEachIndexed { index, slot ->
            val look = looks.getOrNull(index) ?: return@forEachIndexed
            drawPill(look, box = slot.size, centre = slot.centre)
        }
    }
    return bitmap.asAndroidBitmap()
}
