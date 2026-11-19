package io.github.lgpy.familycare.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.lgpy.familycare.core.domain.circle.PatientId

/**
 * Resolves the identity colour of a patient.
 *
 * A function rather than a map so a caller can supply an order-based
 * assignment (below) without materialising a colour for every patient.
 */
typealias PatientColorResolver = (PatientId) -> Color

/**
 * The palette currently in use (light or dark), provided by
 * [FamilyCareTheme]. Kept alongside [LocalPatientColors] so an order-based
 * resolver can be built inside the theme and follow the mode.
 */
internal val LocalPatientPalette = staticCompositionLocalOf { PatientPaletteLight }

/**
 * How [io.github.lgpy.familycare.core.ui.components.PatientAvatar] finds a
 * patient's colour.
 *
 * The default is a stable hash of the patient id, so a patient keeps their
 * colour everywhere with no wiring at all. **Order-based resolution** is also
 * available via [rememberOrderedPatientColors]: it assigns the palette in id
 * order, so a circle of up to eight patients never has two identical avatars.
 * The app shell does not provide it yet (phase 1); see
 * `docs/ux/design-system.md`.
 */
val LocalPatientColors = staticCompositionLocalOf<PatientColorResolver> {
    hashPatientColors(PatientPaletteLight)
}

/**
 * The default resolver: a stable hash of the id into [palette].
 *
 * `String.hashCode` is specified by the language (not identity-based), so the
 * same patient gets the same colour on every phone and after every restart.
 */
fun hashPatientColors(palette: List<Color>): PatientColorResolver {
    require(palette.isNotEmpty()) { "The patient palette must not be empty" }
    return { patientId -> palette[Math.floorMod(patientId.value.hashCode(), palette.size)] }
}

/**
 * Order-based resolution: sort the circle's patients by id and hand out the
 * palette in order, wrapping after eight.
 *
 * Id order is used as the stable key because the patient model has no creation
 * timestamp on the phone; ids are generated once and never change. A patient
 * missing from [patientIds] (e.g. a list still loading) falls back to the hash
 * rather than borrowing someone else's colour.
 */
fun orderedPatientColors(
    patientIds: List<PatientId>,
    palette: List<Color>,
): PatientColorResolver {
    require(palette.isNotEmpty()) { "The patient palette must not be empty" }
    val assigned = patientIds
        .distinct()
        .sortedBy { it.value }
        .withIndex()
        .associate { (index, id) -> id to palette[index % palette.size] }
    val fallback = hashPatientColors(palette)
    return { patientId -> assigned[patientId] ?: fallback(patientId) }
}

/**
 * [orderedPatientColors] in the current mode, for a shell that has the
 * circle's patient list at hand:
 *
 * ```kotlin
 * CompositionLocalProvider(
 *     LocalPatientColors provides rememberOrderedPatientColors(patients.map { it.id }),
 * ) { ... }
 * ```
 */
@Composable
fun rememberOrderedPatientColors(patientIds: List<PatientId>): PatientColorResolver {
    val palette = LocalPatientPalette.current
    return remember(patientIds, palette) { orderedPatientColors(patientIds, palette) }
}
