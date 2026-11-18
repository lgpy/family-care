package io.github.lgpy.familycare.feature.medications.reminders

import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the alarms in step with the data while the app is running.
 *
 * Triggers (docs/build-plan.md M4):
 * - app start: one immediate reschedule, before any user action;
 * - any change in patients, active medications/regimens, dose log, memberships
 *   or the session. The flows are debounced by two seconds so a multi-row sync
 *   or a group confirmation produces one pass, not one per row.
 *
 * Boot, time/time-zone changes, app updates and the daily safety alarm are
 * broadcasts, handled by [ReminderRescheduleReceiver].
 *
 * The dose-log flow is derived from the active regimens rather than
 * `dosesToday`, because the latter emits a minute tick: rescheduling on every
 * tick would be pointless work (the plan only changes when data or time does,
 * and time changes are covered by the daily alarm).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ReminderSchedulerCoordinator(
    private val scope: CoroutineScope,
    private val scheduler: DoseReminderScheduler,
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val patientRepository: PatientRepository,
    private val careCircleRepository: CareCircleRepository,
    private val authRepository: AuthRepository,
) {

    fun start() {
        scope.launch {
            runCatching { scheduler.reschedule() }
            combine(
                patientRepository.patients,
                medicationRepository.activeMedications,
                careCircleRepository.myRole,
                careCircleRepository.members,
                authRepository.state,
            ) { patients, medications, role, members, auth ->
                Trigger(patients.size, medications.size, role, members.size, auth.toString())
            }
                .debounce(DEBOUNCE_MILLIS)
                .collect {
                    runCatching { scheduler.reschedule() }
                }
        }

        scope.launch {
            // Dose-log changes are their own flow: observe the active regimens
            // and any entry in a window wide enough for the 48 h plan. A new
            // medication re-subscribes through flatMapLatest.
            medicationRepository.activeMedications
                .flatMapLatest { medications ->
                    val ids = medications.flatMap { it.regimens }.map { it.id }.distinct()
                    if (ids.isEmpty()) {
                        flowOf(0)
                    } else {
                        val today = LocalDate.now(ZoneId.systemDefault())
                        doseLogRepository.observeEntries(
                            regimenIds = ids,
                            from = today.minusDays(2),
                            to = today.plusDays(4),
                        ).map { entries -> entries.size }
                    }
                }
                .debounce(DEBOUNCE_MILLIS)
                .collect {
                    runCatching { scheduler.reschedule() }
                }
        }
    }

    /** Only used to make every contributing flow part of the combined trigger. */
    private data class Trigger(
        val patientCount: Int,
        val medicationCount: Int,
        val role: Any?,
        val memberCount: Int,
        val auth: String,
    )

    private companion object {
        const val DEBOUNCE_MILLIS = 2_000L
    }
}
