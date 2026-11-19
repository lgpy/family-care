package io.github.lgpy.familycare.core.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.medication.PillColor
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.domain.medication.PillShape
import io.github.lgpy.familycare.core.domain.medication.PillSize
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import java.util.Locale

/**
 * The fixed pill palette of D33 (docs/ux/mockups/screens-v2.html, `pill()`).
 *
 * Literal values, identical in light and dark: a pill is a picture of a real
 * object, not a theme colour, so it must not follow the wallpaper or the theme.
 * White is the mockup's warm off-white rather than #FFFFFF, so the outline and
 * the highlight stay visible on it.
 */
private val PillPalette: Map<PillColor, Color> = mapOf(
    PillColor.WHITE to Color(0xFFF7F5F0),
    PillColor.CREAM to Color(0xFFF3E6C4),
    PillColor.YELLOW to Color(0xFFF6D04D),
    PillColor.ORANGE to Color(0xFFF29A4A),
    PillColor.PINK to Color(0xFFF2A7BD),
    PillColor.RED to Color(0xFFD9534F),
    PillColor.BLUE to Color(0xFF7FB3E6),
    PillColor.NAVY to Color(0xFF2F4F8F),
    PillColor.GREEN to Color(0xFF7CC48A),
    PillColor.BROWN to Color(0xFF9B6B4A),
    PillColor.PURPLE to Color(0xFFA88BD8),
    PillColor.GREY to Color(0xFFB9BCC2),
)

/**
 * The fixed colour of a palette token, for a pill-look customizer's swatches.
 *
 * The map itself stays private so the palette cannot be extended from outside
 * `core/ui`; a screen that needs to draw a swatch reads the value through here
 * (D33).
 */
fun pillColorSwatch(color: PillColor): Color = PillPalette.getValue(color)

/** The mockup's outline: black at 28%, so it reads on every palette colour. */
private val PillOutline = Color.Black.copy(alpha = 0.28f)

/** The mockup's highlight over the fill. */
private val PillHighlight = Color.White.copy(alpha = 0.55f)

/** The dark shade of the drop mark on the bottle, and of a capsule with no second colour. */
private val PillShade = Color.Black.copy(alpha = 0.25f)
private val PillCapNeutral = Color(0xFF555555)

/** The drawing box the mockup scales its stroke widths against. */
private const val DrawingBoxDp = 40f

/** The pill's share of a [PillTile]: the mockup draws 48 in 56 and 34 in 40. */
private const val PillInTileFraction = 0.85f

/**
 * Draws how a medicine looks: shape, colour, optional second colour, score line
 * and relative size (D33).
 *
 * The values are the `pill_*` columns of a medication, parsed into a [PillLook]
 * by the domain. A null look (no shape set, or a row from a server that knows a
 * shape this app version does not) draws the neutral outlined pill glyph, never
 * an empty box.
 *
 * Every shape is drawn with the mockup's proportions on a square box of [size]:
 * the fill, a black 28% outline scaled with the box, a white 55% highlight and,
 * when [PillLook.score] is set, a score line. The fixed palette is the same in
 * light and dark. [PillSize.scale] shrinks or grows the drawing inside the box,
 * so a small pill never changes the row's height.
 *
 * The picture is decorative next to the medicine's real text, but when it
 * stands alone (a form preview) TalkBack gets the built description from
 * [pillDescription]; pass [contentDescription] to override it, or null to hide
 * the picture from accessibility.
 */
@Composable
fun PillImage(
    look: PillLook?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = pillDescription(look).ifBlank { null },
) {
    if (look == null) {
        Icon(
            painter = painterResource(R.drawable.ic_sym_pill),
            contentDescription = contentDescription,
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val description = contentDescription
    val semanticsModifier = if (description == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = description }
    }
    Canvas(modifier = semanticsModifier.size(size)) {
        drawPill(look)
    }
}

/**
 * The mockup's list tile: a rounded square of `surfaceContainerHigh` with the
 * pill centred in it, as the medication rows, dose slots and profile tabs use.
 *
 * The pill is drawn at [PillInTileFraction] of the tile, so the tile's 40 dp and
 * 56 dp variants match the mockup's 34 dp and 48 dp pills. The corner radius is
 * 12 dp at 40 dp and 16 dp at 56 dp, interpolated in between.
 */
@Composable
fun PillTile(
    look: PillLook?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    contentDescription: String? = pillDescription(look).ifBlank { null },
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(pillTileRadius(size)))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        PillImage(
            look = look,
            size = size * PillInTileFraction,
            contentDescription = contentDescription,
        )
    }
}

/** The tile's corner radius: 12 dp at 40 dp, 16 dp at 56 dp, linear between. */
private fun pillTileRadius(size: Dp): Dp = when {
    size <= 40.dp -> 12.dp
    size >= 56.dp -> 16.dp
    else -> 12.dp + (size - 40.dp) * (4f / 16f)
}

/**
 * What TalkBack reads for [look], built from its parts and localised.
 *
 * Examples: "White long tablet with a score line", "Purple and pink capsule",
 * "Small yellow round tablet", "Drops" / pt-PT: "Comprimido comprido branco
 * com ranhura", "Cápsula roxa e rosa", "Comprimido redondo amarelo pequeno",
 * "Gotas". The sentence is assembled from per-shape, per-colour and per-size
 * resources so a translation can reorder the adjectives; Portuguese puts the
 * noun first and makes the colour agree with "cápsula".
 *
 * A null look returns the empty string: there is no picture to describe, and
 * the design system asks that it be announced as nothing extra.
 */
@Composable
fun pillDescription(look: PillLook?): String {
    if (look == null) return ""
    if (look.shape == PillShape.DROPS) return stringResource(R.string.pill_shape_drops)

    val feminine = look.shape == PillShape.CAPSULE
    val secondColour = look.color2
    val firstColour = pillColorName(look.color ?: PillColor.WHITE, feminine)
    val colourPhrase = if (feminine && secondColour != null) {
        stringResource(
            R.string.pill_desc_two_colours,
            firstColour,
            pillColorName(secondColour, feminine = true),
        )
    } else {
        firstColour
    }

    var text = stringResource(
        R.string.pill_desc_core,
        colourPhrase,
        stringResource(pillShapeName(look.shape)),
    )
    if (look.score) text = stringResource(R.string.pill_desc_scored, text)
    text = when (look.size) {
        PillSize.SMALL -> stringResource(
            R.string.pill_desc_sized,
            stringResource(if (feminine) R.string.pill_size_small_fem else R.string.pill_size_small),
            text,
        )

        PillSize.LARGE -> stringResource(
            R.string.pill_desc_sized,
            stringResource(if (feminine) R.string.pill_size_large_fem else R.string.pill_size_large),
            text,
        )

        PillSize.MEDIUM -> text
    }
    return text.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** The name resource of a non-drops shape; drops are handled before this. */
private fun pillShapeName(shape: PillShape): Int = when (shape) {
    PillShape.ROUND -> R.string.pill_shape_round
    PillShape.OVAL -> R.string.pill_shape_oval
    PillShape.OBLONG -> R.string.pill_shape_oblong
    PillShape.CAPSULE -> R.string.pill_shape_capsule
    PillShape.DIAMOND -> R.string.pill_shape_diamond
    PillShape.TRIANGLE -> R.string.pill_shape_triangle
    PillShape.DROPS -> R.string.pill_shape_drops
}

/**
 * The colour word; [feminine] picks the form that agrees with "cápsula".
 *
 * Exposed as well as used by [pillDescription], because a customizer's swatches
 * need the same localised colour name for their content description.
 */
@Composable
fun pillColorName(colour: PillColor, feminine: Boolean = false): String = stringResource(
    when (colour) {
        PillColor.WHITE -> if (feminine) R.string.pill_color_white_fem else R.string.pill_color_white
        PillColor.CREAM -> if (feminine) R.string.pill_color_cream_fem else R.string.pill_color_cream
        PillColor.YELLOW -> if (feminine) R.string.pill_color_yellow_fem else R.string.pill_color_yellow
        PillColor.ORANGE -> if (feminine) R.string.pill_color_orange_fem else R.string.pill_color_orange
        PillColor.PINK -> if (feminine) R.string.pill_color_pink_fem else R.string.pill_color_pink
        PillColor.RED -> if (feminine) R.string.pill_color_red_fem else R.string.pill_color_red
        PillColor.BLUE -> if (feminine) R.string.pill_color_blue_fem else R.string.pill_color_blue
        PillColor.NAVY -> if (feminine) R.string.pill_color_navy_fem else R.string.pill_color_navy
        PillColor.GREEN -> if (feminine) R.string.pill_color_green_fem else R.string.pill_color_green
        PillColor.BROWN -> if (feminine) R.string.pill_color_brown_fem else R.string.pill_color_brown
        PillColor.PURPLE -> if (feminine) R.string.pill_color_purple_fem else R.string.pill_color_purple
        PillColor.GREY -> if (feminine) R.string.pill_color_grey_fem else R.string.pill_color_grey
    },
)

/**
 * One pill on the canvas, in the mockup's proportions.
 *
 * All coordinates are fractions of the square box, exactly as `pill()` in
 * docs/ux/mockups/screens-v2.html computes them from its pixel size, so the
 * shape is identical at 24 dp, 40 dp and 72 dp. [PillSize.scale] multiplies the
 * body inside the box, never the box itself.
 */
private fun DrawScope.drawPill(look: PillLook) {
    val box = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val centre = Offset(cx, cy)
    val k = look.size.scale
    val strokeWidth = 1.2f * box / DrawingBoxDp
    val scoreWidth = 1.4f * box / DrawingBoxDp
    val outline = Stroke(width = strokeWidth)
    val fill = PillPalette[look.color] ?: PillPalette.getValue(PillColor.WHITE)
    val second = PillPalette[look.color2] ?: fill

    when (look.shape) {
        PillShape.ROUND -> {
            val r = box * 0.30f * k
            drawCircle(fill, r, centre)
            drawCircle(PillOutline, r, centre, style = outline)
            drawOval(
                color = PillHighlight,
                topLeft = Offset(cx - r * 0.3f - r * 0.38f, cy - r * 0.35f - r * 0.2f),
                size = Size(r * 0.76f, r * 0.4f),
            )
            if (look.score) {
                drawLine(
                    PillOutline,
                    Offset(cx - r * 0.75f, cy),
                    Offset(cx + r * 0.75f, cy),
                    scoreWidth,
                    StrokeCap.Round,
                )
            }
        }

        PillShape.OVAL -> {
            val rx = box * 0.36f * k
            val ry = box * 0.23f * k
            rotate(-25f, centre) {
                drawOval(fill, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f))
                drawOval(PillOutline, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f), style = outline)
                drawOval(
                    color = PillHighlight,
                    topLeft = Offset(cx - rx * 0.65f, cy - ry * 0.67f),
                    size = Size(rx * 0.7f, ry * 0.44f),
                )
                if (look.score) {
                    drawLine(
                        PillOutline,
                        Offset(cx, cy - ry * 0.8f),
                        Offset(cx, cy + ry * 0.8f),
                        scoreWidth,
                        StrokeCap.Round,
                    )
                }
            }
        }

        PillShape.OBLONG -> {
            val w = box * 0.78f * k
            val h = box * 0.34f * k
            val left = cx - w / 2f
            val top = cy - h / 2f
            rotate(-30f, centre) {
                drawRoundRect(fill, Offset(left, top), Size(w, h), CornerRadius(h / 2f))
                drawRoundRect(
                    PillOutline,
                    Offset(left, top),
                    Size(w, h),
                    CornerRadius(h / 2f),
                    style = outline,
                )
                drawRoundRect(
                    color = PillHighlight,
                    topLeft = Offset(left + h * 0.35f, top + h * 0.18f),
                    size = Size(w * 0.5f, h * 0.2f),
                    cornerRadius = CornerRadius(h * 0.1f),
                )
                if (look.score) {
                    drawLine(
                        PillOutline,
                        Offset(cx, cy - h * 0.4f),
                        Offset(cx, cy + h * 0.4f),
                        scoreWidth,
                        StrokeCap.Round,
                    )
                }
            }
        }

        PillShape.CAPSULE -> {
            val w = box * 0.80f * k
            val h = box * 0.32f * k
            val left = cx - w / 2f
            val top = cy - h / 2f
            rotate(-30f, centre) {
                val body = Path().apply {
                    addRoundRect(
                        RoundRect(left, top, left + w, top + h, CornerRadius(h / 2f)),
                    )
                }
                clipPath(body) {
                    drawRect(fill, Offset(left, top), Size(w / 2f, h))
                    drawRect(second, Offset(cx, top), Size(w / 2f, h))
                }
                drawRoundRect(
                    PillOutline,
                    Offset(left, top),
                    Size(w, h),
                    CornerRadius(h / 2f),
                    style = outline,
                )
                drawLine(PillOutline, Offset(cx, top), Offset(cx, top + h), strokeWidth)
                drawRoundRect(
                    color = PillHighlight,
                    topLeft = Offset(left + h * 0.35f, top + h * 0.18f),
                    size = Size(w * 0.55f, h * 0.2f),
                    cornerRadius = CornerRadius(h * 0.1f),
                )
            }
        }

        PillShape.DIAMOND -> {
            val r = box * 0.32f * k
            val side = r * 1.44f
            rotate(45f, centre) {
                drawRoundRect(
                    fill,
                    Offset(cx - side / 2f, cy - side / 2f),
                    Size(side, side),
                    CornerRadius(r * 0.3f),
                )
                drawRoundRect(
                    PillOutline,
                    Offset(cx - side / 2f, cy - side / 2f),
                    Size(side, side),
                    CornerRadius(r * 0.3f),
                    style = outline,
                )
                drawOval(
                    color = PillHighlight,
                    topLeft = Offset(cx - r * 0.44f, cy - r * 0.42f),
                    size = Size(r * 0.44f, r * 0.24f),
                )
                if (look.score) {
                    drawLine(
                        PillOutline,
                        Offset(cx - r * 0.7f, cy),
                        Offset(cx + r * 0.7f, cy),
                        scoreWidth,
                        StrokeCap.Round,
                    )
                }
            }
        }

        PillShape.TRIANGLE -> {
            val r = box * 0.34f * k
            val path = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r * 0.95f, cy + r * 0.7f)
                lineTo(cx - r * 0.95f, cy + r * 0.7f)
                close()
            }
            // Fill, then the same path stroked in the fill colour with round
            // joins to round the corners, then the real outline on top.
            drawPath(path, fill)
            drawPath(path, fill, style = Stroke(width = r * 0.25f, join = StrokeJoin.Round))
            drawPath(path, PillOutline, style = Stroke(width = strokeWidth, join = StrokeJoin.Round))
            clipPath(path) {
                drawOval(
                    color = PillHighlight,
                    topLeft = Offset(cx - r * 0.45f, cy - r * 0.55f),
                    size = Size(r * 0.4f, r * 0.22f),
                )
            }
            if (look.score) {
                drawLine(
                    PillOutline,
                    Offset(cx - r * 0.55f, cy),
                    Offset(cx + r * 0.55f, cy),
                    scoreWidth,
                    StrokeCap.Round,
                )
            }
        }

        PillShape.DROPS -> {
            val bodyWidth = box * 0.34f
            val bodyHeight = box * 0.42f
            val bodyTop = box * 0.12f
            val capColour = PillPalette[look.color2] ?: PillCapNeutral
            // The mockup does not grow the bottle with the size token; scaling
            // around the centre follows the other shapes without changing the
            // bottle's proportions.
            scale(k, pivot = centre) {
                val left = cx - bodyWidth / 2f
                val top = cy - bodyTop
                drawRoundRect(
                    fill,
                    Offset(left, top),
                    Size(bodyWidth, bodyHeight),
                    CornerRadius(box * 0.06f),
                )
                drawRoundRect(
                    PillOutline,
                    Offset(left, top),
                    Size(bodyWidth, bodyHeight),
                    CornerRadius(box * 0.06f),
                    style = outline,
                )
                drawRoundRect(
                    capColour,
                    Offset(cx - box * 0.08f, cy - box * 0.30f),
                    Size(box * 0.16f, box * 0.18f),
                    CornerRadius(box * 0.03f),
                )
                drawRoundRect(
                    color = PillHighlight,
                    topLeft = Offset(left + box * 0.05f, top + box * 0.04f),
                    size = Size(box * 0.07f, box * 0.12f),
                    cornerRadius = CornerRadius(box * 0.03f),
                )
                val drop = Path().apply {
                    moveTo(cx, cy + box * 0.02f)
                    cubicTo(
                        cx + box * 0.06f, cy + box * 0.10f,
                        cx + box * 0.07f, cy + box * 0.13f,
                        cx, cy + box * 0.18f,
                    )
                    cubicTo(
                        cx - box * 0.07f, cy + box * 0.13f,
                        cx - box * 0.06f, cy + box * 0.10f,
                        cx, cy + box * 0.02f,
                    )
                    close()
                }
                drawPath(drop, PillShade)
            }
        }
    }
}

/** The mockup's medicine looks, so the preview shows the real proportions. */
private val PreviewLookOblong = PillLook(
    shape = PillShape.OBLONG,
    color = PillColor.WHITE,
    score = true,
)
private val PreviewLookRound = PillLook(
    shape = PillShape.ROUND,
    color = PillColor.WHITE,
    score = true,
)
private val PreviewLookOval = PillLook(shape = PillShape.OVAL, color = PillColor.PINK)
private val PreviewLookCapsule = PillLook(
    shape = PillShape.CAPSULE,
    color = PillColor.PURPLE,
    color2 = PillColor.PINK,
)
private val PreviewLookSmallRound = PillLook(
    shape = PillShape.ROUND,
    color = PillColor.YELLOW,
    size = PillSize.SMALL,
)
private val PreviewLookDrops = PillLook(
    shape = PillShape.DROPS,
    color = PillColor.BROWN,
    color2 = PillColor.WHITE,
)
private val PreviewLookDiamond = PillLook(shape = PillShape.DIAMOND, color = PillColor.BLUE)
private val PreviewLookTriangle = PillLook(
    shape = PillShape.TRIANGLE,
    color = PillColor.GREEN,
    size = PillSize.LARGE,
)

@Preview(name = "Pills light", widthDp = 384, showBackground = true)
@Preview(
    name = "Pills dark",
    widthDp = 384,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PillImagePreview() {
    FamilyCareTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Every shape", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        PreviewLookRound,
                        PreviewLookOval,
                        PreviewLookOblong,
                        PreviewLookCapsule,
                        PreviewLookDiamond,
                        PreviewLookTriangle,
                        PreviewLookDrops,
                    ).forEach { PillImage(look = it) }
                }

                Text("A few colours", style = MaterialTheme.typography.labelMedium)
                val colours = listOf(
                    PillColor.WHITE,
                    PillColor.CREAM,
                    PillColor.YELLOW,
                    PillColor.ORANGE,
                    PillColor.PINK,
                    PillColor.RED,
                    PillColor.BLUE,
                    PillColor.NAVY,
                    PillColor.GREEN,
                    PillColor.BROWN,
                    PillColor.PURPLE,
                    PillColor.GREY,
                )
                colours.chunked(6).forEach { rowColours ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowColours.forEach { colour ->
                            PillImage(look = PillLook(shape = PillShape.ROUND, color = colour))
                        }
                    }
                }

                Text("24 / 40 / 72 dp", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillImage(look = PreviewLookSmallRound, size = 24.dp)
                    PillImage(look = PreviewLookOblong, size = 40.dp)
                    PillImage(look = PreviewLookCapsule, size = 72.dp)
                }

                Text("Tiles and a missing look", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillTile(look = PreviewLookOblong)
                    PillTile(look = PreviewLookCapsule, size = 40.dp)
                    PillTile(look = PreviewLookDrops, size = 40.dp)
                    PillTile(look = null, size = 40.dp)
                }
            }
        }
    }
}
