package io.github.lgpy.familycare.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

private val LocalFamilyCareColors = staticCompositionLocalOf { LightFamilyCareColors }
private val LocalFamilyCareType = staticCompositionLocalOf { DefaultFamilyCareType }

/**
 * The app's Material 3 theme.
 *
 * Colour follows the phone's wallpaper (Material You, D32) whenever
 * [dynamicColor] is true; minSdk 33 means the dynamic schemes are always
 * available. Previews and the `dynamicColor = false` fallback use the static
 * green preset. The fixed semantic colours ([FamilyCareTheme.colors]) and the
 * patient palette ([LocalPatientColors]) never follow the wallpaper.
 *
 * Type is Atkinson Hyperlegible Next with tabular numbers
 * ([FamilyCareTheme.type] adds the time styles).
 *
 * Only the standard M3 touch-target minimum is applied (48 dp); there is no
 * in-app density setting, so the system font size and display size do the
 * scaling.
 */
@Composable
fun FamilyCareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // A preview must always show the brand scheme: the wallpaper of the
    // machine rendering it is meaningless.
    val materialYou = dynamicColor && !LocalInspectionMode.current
    val colorScheme = when {
        materialYou && darkTheme -> dynamicDarkColorScheme(context)
        materialYou -> dynamicLightColorScheme(context)
        darkTheme -> GreenPreset.dark
        else -> GreenPreset.light
    }
    val patientPalette = if (darkTheme) PatientPaletteDark else PatientPaletteLight

    CompositionLocalProvider(
        LocalFamilyCareColors provides if (darkTheme) DarkFamilyCareColors else LightFamilyCareColors,
        LocalFamilyCareType provides DefaultFamilyCareType,
        LocalPatientPalette provides patientPalette,
        LocalPatientColors provides remember(patientPalette) { hashPatientColors(patientPalette) },
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FamilyCareTypography,
            content = content,
        )
    }
}

/**
 * Theme accessors in the Material style: `FamilyCareTheme.colors` for the
 * semantic colours, `FamilyCareTheme.type` for the styles M3 does not have.
 */
object FamilyCareTheme {

    /** Fixed semantic colours (attention, critical); never from the wallpaper. */
    val colors: FamilyCareColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFamilyCareColors.current

    /** The time rail and appointment-number styles. */
    val type: FamilyCareType
        @Composable
        @ReadOnlyComposable
        get() = LocalFamilyCareType.current
}
