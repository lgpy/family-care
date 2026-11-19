package io.github.lgpy.familycare.feature.medications.profile

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryIcon
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryItem
import io.github.lgpy.familycare.core.ui.patient.PatientSummarySource
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryText
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryTone
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The medications module's line on the Patients list: how many medicines the
 * patient takes, and a tag when one of them is about to run out.
 *
 * It reads [StockRepository.overviews], the flow the shopping list and the
 * Today attention card already use: it holds every active (not stopped)
 * medication of the circle with its derived stock summary, so the count and the
 * low-stock tag come from one watch and no extra query per patient.
 *
 * Text is localized here through [PatientSummaryText], so the app's Patients
 * tab needs to know nothing about medications (D5). A patient with no active
 * medication contributes nothing at all.
 */
class MedicationsPatientSummarySource(
    private val stockRepository: StockRepository,
    private val text: PatientSummaryText,
) : PatientSummarySource {

    /** After any header-like fact the shell owns, before appointments. */
    override val order: Int = ORDER

    override fun summary(patientId: PatientId): Flow<List<PatientSummaryItem>> =
        stockRepository.overviews.map { overviews ->
            val mine = overviews.filter { it.medication.patientId == patientId }
            buildList {
                if (mine.isNotEmpty()) {
                    add(
                        PatientSummaryItem(
                            icon = PatientSummaryIcon.MEDICATION,
                            text = text.quantity(
                                R.plurals.patients_summary_medicines,
                                mine.size,
                                mine.size,
                            ),
                        ),
                    )
                }
                mine.firstOrNull { it.low }?.let { low ->
                    add(
                        PatientSummaryItem(
                            icon = PatientSummaryIcon.ERROR,
                            text = text.get(
                                R.string.patients_summary_low_stock,
                                low.medication.name,
                            ),
                            tone = PatientSummaryTone.ATTENTION,
                        ),
                    )
                }
            }
        }

    companion object {

        /** Before appointments' summary (20). */
        const val ORDER: Int = 10
    }
}
