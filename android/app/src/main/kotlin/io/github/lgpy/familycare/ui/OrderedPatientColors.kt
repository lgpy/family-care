package io.github.lgpy.familycare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.ui.theme.LocalPatientColors
import io.github.lgpy.familycare.core.ui.theme.rememberOrderedPatientColors
import org.koin.compose.koinInject

/**
 * Gives every patient in the circle a distinct identity colour (D32).
 *
 * `LocalPatientColors` defaults to a stable hash of the patient id, which can
 * collide once a circle has more than one patient. Here the circle's whole
 * patient list — archived patients included, so archiving someone never
 * re-colours everybody else — is handed to [rememberOrderedPatientColors] once
 * at the app shell, and every screen below (the shell's own screens and every
 * feature module's) resolves the same palette.
 *
 * The ordering key is the patient id, which is generated once on the phone and
 * never changes; the domain patient carries no creation timestamp, so there is
 * no earlier stable key to sort by. [rememberOrderedPatientColors] sorts the ids
 * itself, which keeps the assignment identical on both phones.
 */
@Composable
fun OrderedPatientColors(content: @Composable () -> Unit) {
    val patientRepository = koinInject<PatientRepository>()
    val active by patientRepository.patients.collectAsState(initial = emptyList())
    val archived by patientRepository.archivedPatients.collectAsState(initial = emptyList())

    val patientIds = remember(active, archived) {
        (active + archived).map { it.id }
    }
    val colors = rememberOrderedPatientColors(patientIds)

    CompositionLocalProvider(
        LocalPatientColors provides colors,
        content = content,
    )
}
