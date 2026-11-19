package io.github.lgpy.familycare.feature.patientinfo.profile

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.patient.PatientAlerts
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The patient-info module's answer to the core [PatientAlerts] contract (D5).
 *
 * The allergies are the module's own data; the Give screen in the medications
 * module shows the same banner through this interface, without importing
 * patient-info. The app shell registers this implementation in Koin
 * (`di/AppModule.kt`), and a screen that gets no implementation uses
 * [io.github.lgpy.familycare.core.ui.patient.NoPatientAlerts].
 *
 * A patient with no info row reports no allergies, never "none recorded":
 * absence of a record is not a clinical statement.
 */
class PatientInfoAlerts(
    private val repository: PatientInfoRepository,
) : PatientAlerts {

    override fun allergies(patientId: PatientId): Flow<List<String>> =
        repository.observe(patientId)
            .map { info -> info?.allergies.orEmpty() }
            .distinctUntilChanged()
}
