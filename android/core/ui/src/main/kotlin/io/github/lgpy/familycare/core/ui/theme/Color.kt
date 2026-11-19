package io.github.lgpy.familycare.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * Colour tokens (docs/ux/design-system.md).
 *
 * Two kinds of colour live here and they never mix:
 *
 * - The Material colour scheme, which follows the phone's wallpaper under
 *   Material You and falls back to the static green preset below.
 * - The fixed semantic colours (attention, critical) and the patient identity
 *   palette, which are the same on every phone: a warning is never recoloured
 *   by a wallpaper. See [FamilyCareColors].
 */

/**
 * One static preset: the M3 schemes used when Material You is off (and always
 * in previews). The green roles are the ones from
 * `docs/ux/mockups/scaling.html`; the remaining roles are filled out so every
 * M3 slot is sensible.
 *
 * Presets beyond green arrive later; adding one means adding an instance of
 * this class, not changing the theme's signature.
 */
internal data class FamilyCarePreset(
    val light: ColorScheme,
    val dark: ColorScheme,
)

/** The green preset: the app's own brand scheme (mockup `data-seed="green"`). */
internal val GreenPreset = FamilyCarePreset(
    light = lightColorScheme(
        primary = Color(0xFF3A6A4F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBCEFCF),
        onPrimaryContainer = Color(0xFF00210F),
        inversePrimary = Color(0xFFA1D3B4),
        secondary = Color(0xFF4F6352),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD2E8D6),
        onSecondaryContainer = Color(0xFF0E1F14),
        tertiary = Color(0xFF6F5B3E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF7DEBF),
        onTertiaryContainer = Color(0xFF271905),
        background = Color(0xFFF6FBF4),
        onBackground = Color(0xFF171D19),
        surface = Color(0xFFF6FBF4),
        onSurface = Color(0xFF171D19),
        surfaceVariant = Color(0xFFDCE5DC),
        onSurfaceVariant = Color(0xFF404943),
        surfaceTint = Color(0xFF3A6A4F),
        inverseSurface = Color(0xFF2C322D),
        inverseOnSurface = Color(0xFFEDF2EC),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF707A72),
        outlineVariant = Color(0xFFC0C9C1),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFF6FBF4),
        surfaceDim = Color(0xFFD6DBD6),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF0F5EE),
        surfaceContainer = Color(0xFFEAEFE8),
        surfaceContainerHigh = Color(0xFFE4EAE3),
        surfaceContainerHighest = Color(0xFFDFE4DD),
        primaryFixed = Color(0xFFBCEFCF),
        primaryFixedDim = Color(0xFFA1D3B4),
        onPrimaryFixed = Color(0xFF00210F),
        onPrimaryFixedVariant = Color(0xFF215239),
        secondaryFixed = Color(0xFFD2E8D6),
        secondaryFixedDim = Color(0xFFB6CCBA),
        onSecondaryFixed = Color(0xFF0E1F14),
        onSecondaryFixedVariant = Color(0xFF374B3D),
        tertiaryFixed = Color(0xFFF7DEBF),
        tertiaryFixedDim = Color(0xFFDAC2A3),
        onTertiaryFixed = Color(0xFF271905),
        onTertiaryFixedVariant = Color(0xFF56442A),
    ),
    dark = darkColorScheme(
        primary = Color(0xFFA1D3B4),
        onPrimary = Color(0xFF053822),
        primaryContainer = Color(0xFF215239),
        onPrimaryContainer = Color(0xFFBCEFCF),
        inversePrimary = Color(0xFF3A6A4F),
        secondary = Color(0xFFB6CCBA),
        onSecondary = Color(0xFF223527),
        secondaryContainer = Color(0xFF374B3D),
        onSecondaryContainer = Color(0xFFD2E8D6),
        tertiary = Color(0xFFDAC2A3),
        onTertiary = Color(0xFF3D2E16),
        tertiaryContainer = Color(0xFF56442A),
        onTertiaryContainer = Color(0xFFF7DEBF),
        background = Color(0xFF0F1511),
        onBackground = Color(0xFFDEE4DD),
        surface = Color(0xFF0F1511),
        onSurface = Color(0xFFDEE4DD),
        surfaceVariant = Color(0xFF404943),
        onSurfaceVariant = Color(0xFFC0C9C1),
        surfaceTint = Color(0xFFA1D3B4),
        inverseSurface = Color(0xFFDEE4DD),
        inverseOnSurface = Color(0xFF2C322D),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8A938B),
        outlineVariant = Color(0xFF404943),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF353B36),
        surfaceDim = Color(0xFF0F1511),
        surfaceContainerLowest = Color(0xFF0A0F0B),
        surfaceContainerLow = Color(0xFF171D19),
        surfaceContainer = Color(0xFF1B211D),
        surfaceContainerHigh = Color(0xFF252B27),
        surfaceContainerHighest = Color(0xFF303631),
        primaryFixed = Color(0xFFBCEFCF),
        primaryFixedDim = Color(0xFFA1D3B4),
        onPrimaryFixed = Color(0xFF00210F),
        onPrimaryFixedVariant = Color(0xFF215239),
        secondaryFixed = Color(0xFFD2E8D6),
        secondaryFixedDim = Color(0xFFB6CCBA),
        onSecondaryFixed = Color(0xFF0E1F14),
        onSecondaryFixedVariant = Color(0xFF374B3D),
        tertiaryFixed = Color(0xFFF7DEBF),
        tertiaryFixedDim = Color(0xFFDAC2A3),
        onTertiaryFixed = Color(0xFF271905),
        onTertiaryFixedVariant = Color(0xFF56442A),
    ),
)

/**
 * Semantic colours that never follow the wallpaper.
 *
 * - **Attention** (amber): something needs doing — an unconfirmed dose slot, a
 *   medication running low. The accent is the icon/button colour on its
 *   container.
 * - **Critical** (red): allergies. The only red in the product; a dose that is
 *   merely done is neutral, never red and never green.
 *
 * Values are the fixed ones in `docs/ux/mockups/scaling.html`.
 */
@Immutable
data class FamilyCareColors(
    val attentionContainer: Color,
    val onAttentionContainer: Color,
    val attentionAccent: Color,
    val criticalContainer: Color,
    val onCriticalContainer: Color,
    val criticalAccent: Color,
)

internal val LightFamilyCareColors = FamilyCareColors(
    attentionContainer = Color(0xFFFFDDB3),
    onAttentionContainer = Color(0xFF2A1800),
    attentionAccent = Color(0xFF8A5100),
    criticalContainer = Color(0xFFFFDAD6),
    onCriticalContainer = Color(0xFF410002),
    criticalAccent = Color(0xFFBA1A1A),
)

internal val DarkFamilyCareColors = FamilyCareColors(
    attentionContainer = Color(0xFF5A3500),
    onAttentionContainer = Color(0xFFFFDDB3),
    attentionAccent = Color(0xFFFFB95C),
    criticalContainer = Color(0xFF93000A),
    onCriticalContainer = Color(0xFFFFDAD6),
    criticalAccent = Color(0xFFFFB4AB),
)

/**
 * The eight patient identity colours, light variants (tone 40).
 *
 * The first four are the mockup's Maria, António, Mom and You. The other four
 * are chosen for a distinct hue at the same tone: blue, green, rose and sand.
 * Red and amber hues are deliberately absent so an identity colour never reads
 * as a warning; "done" is a neutral [ColorScheme.onSurfaceVariant], never
 * green.
 */
internal val PatientPaletteLight: List<Color> = listOf(
    Color(0xFF9A4F00), // orange
    Color(0xFF00687A), // teal
    Color(0xFF6B4EA2), // purple
    Color(0xFF5D6300), // olive
    Color(0xFF3F5F91), // blue
    Color(0xFF3A6A4F), // green
    Color(0xFF8B4A62), // rose
    Color(0xFF6F5B3E), // sand
)

/** The same eight identities at tone 80 for dark mode. */
internal val PatientPaletteDark: List<Color> = listOf(
    Color(0xFFFFB77C),
    Color(0xFF53D7F1),
    Color(0xFFD4BBFF),
    Color(0xFFC6CE6C),
    Color(0xFFA8C8FF),
    Color(0xFFA1D3B4),
    Color(0xFFFFB0C9),
    Color(0xFFDCC3A0),
)
