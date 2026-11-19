package io.github.lgpy.familycare.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.lgpy.familycare.core.ui.R

/*
 * Type tokens (docs/ux/design-system.md §2).
 *
 * The family is Atkinson Hyperlegible Next (SIL OFL, see
 * `android/core/ui/licenses/AtkinsonHyperlegibleNext-OFL.txt`), bundled as one
 * variable font; each weight is a variation setting rather than a separate
 * file. It is the typeface the approved mockup uses, chosen for legibility at
 * arm's length and at large system font sizes.
 *
 * Every style carries `tnum` so times, doses and stock counts line up in
 * columns without jumping as digits change.
 */

private const val TabularNumbers = "tnum"

/**
 * The bundled variable font at one weight.
 *
 * Android reads the file's `wght` axis; the Compose weight keeps font matching
 * (bold styling, `.copy(fontWeight = ...)`) working.
 */
@OptIn(ExperimentalTextApi::class)
private fun atkinson(weight: FontWeight, variationWeight: Int): Font = Font(
    resId = R.font.atkinson_hyperlegible_next,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(variationWeight)),
)

/** Atkinson Hyperlegible Next at the four weights the design uses. */
internal val AtkinsonHyperlegibleNext = FontFamily(
    atkinson(FontWeight.Normal, 400),
    atkinson(FontWeight.Medium, 500),
    atkinson(FontWeight.SemiBold, 600),
    atkinson(FontWeight.Bold, 700),
)

private fun TextStyle.atkinson(): TextStyle = copy(
    fontFamily = AtkinsonHyperlegibleNext,
    fontFeatureSettings = TabularNumbers,
)

/**
 * The M3 type scale with the app's family and tabular numbers. Sizes,
 * weights and line heights are Material's defaults; the system font size
 * setting scales them (sp), and there is no in-app density setting.
 */
internal val FamilyCareTypography: Typography = Typography().let { default ->
    Typography(
        displayLarge = default.displayLarge.atkinson(),
        displayMedium = default.displayMedium.atkinson(),
        displaySmall = default.displaySmall.atkinson(),
        headlineLarge = default.headlineLarge.atkinson(),
        headlineMedium = default.headlineMedium.atkinson(),
        headlineSmall = default.headlineSmall.atkinson(),
        titleLarge = default.titleLarge.atkinson(),
        titleMedium = default.titleMedium.atkinson(),
        titleSmall = default.titleSmall.atkinson(),
        bodyLarge = default.bodyLarge.atkinson(),
        bodyMedium = default.bodyMedium.atkinson(),
        bodySmall = default.bodySmall.atkinson(),
        labelLarge = default.labelLarge.atkinson(),
        labelMedium = default.labelMedium.atkinson(),
        labelSmall = default.labelSmall.atkinson(),
    )
}

/**
 * The two styles the M3 scale does not have, for the time rail and the
 * appointment day numbers.
 *
 * They are deliberately large and bold: a time is the thing a caregiver scans
 * for. [time] is the rail label ("08:00"); [timeSmall] is the 20sp variant for
 * day numbers and other secondary large numbers.
 */
@Immutable
data class FamilyCareType(
    val time: TextStyle = TextStyle(
        fontFamily = AtkinsonHyperlegibleNext,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TabularNumbers,
    ),
    val timeSmall: TextStyle = TextStyle(
        fontFamily = AtkinsonHyperlegibleNext,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TabularNumbers,
    ),
)

internal val DefaultFamilyCareType = FamilyCareType()
