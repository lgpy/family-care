package io.github.lgpy.familycare.feature.medications.profile

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.TimelineEntry
import io.github.lgpy.familycare.core.ui.patient.TimelineKind
import io.github.lgpy.familycare.core.ui.patient.TimelineSource
import io.github.lgpy.familycare.core.ui.patient.TimelineText
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The medications module's contribution to the patient's History tab (D29):
 * one entry when a medication started, one for every later regimen version,
 * and one when it was stopped.
 *
 * Text is localized here, through the [TimelineText] the app builds from its
 * `Resources`, so the shell's History tab needs to know nothing about
 * medications (D5). Every entry opens the medication's detail screen.
 */
class MedicationsTimelineSource(
    private val medicationRepository: MedicationRepository,
    private val text: TimelineText,
) : TimelineSource {

    override fun entries(patientId: PatientId): Flow<List<TimelineEntry>> =
        medicationRepository.observe(patientId).map { medications ->
            medications.flatMap { entriesFor(patientId, it) }.sortedBy { it.at }
        }

    private fun entriesFor(
        patientId: PatientId,
        item: MedicationWithRegimens,
    ): List<TimelineEntry> {
        val medication = item.medication
        val route = MedicationsRoutes.medicationDetail(patientId.value, medication.id.value)
        val displayName = medication.strength
            ?.let { medication.name + " " + it }
            ?: medication.name

        val versions = item.regimens.sortedBy { it.startDate }
        val entries = versions.mapIndexed { index, regimen ->
            val at = regimen.startDate.atStartOfDay(regimen.timeZone).toInstant()
            if (index == 0) {
                TimelineEntry(
                    id = "medication:${medication.id.value}:started:${regimen.id.value}",
                    at = at,
                    title = text.get(R.string.patient_timeline_medication_started, displayName),
                    detail = null,
                    kind = TimelineKind.MEDICATION_STARTED,
                    route = route,
                )
            } else {
                TimelineEntry(
                    id = "medication:${medication.id.value}:changed:${regimen.id.value}",
                    at = at,
                    title = text.get(R.string.patient_timeline_medication_changed, medication.name),
                    detail = text.get(R.string.patient_timeline_medication_changed_detail),
                    kind = TimelineKind.MEDICATION_CHANGED,
                    route = route,
                )
            }
        }

        val stopped = medication.stoppedAt?.let { stoppedAt ->
            TimelineEntry(
                id = "medication:${medication.id.value}:stopped",
                at = stoppedAt,
                title = text.get(R.string.patient_timeline_medication_stopped, medication.name),
                detail = null,
                kind = TimelineKind.MEDICATION_STOPPED,
                route = route,
            )
        }

        return entries + listOfNotNull(stopped)
    }
}
