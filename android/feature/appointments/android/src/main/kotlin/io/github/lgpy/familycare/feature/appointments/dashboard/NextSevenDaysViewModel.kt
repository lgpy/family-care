package io.github.lgpy.familycare.feature.appointments.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.appointments.data.AppointmentEntry
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Today's "Next 7 days" card (docs/domain/appointments.md "Screens"; dashboard
 * order 200).
 *
 * It is a digest of the Agenda, not a second one: the same repository flow
 * (`observeUpcoming`, which excludes cancelled appointments) filtered to the
 * next seven days and cut to [MAX_LINES], with the rest counted as "+N more".
 * Every line carries its appointment id so the card can push the detail screen
 * through the module's route, and the card's header opens the Agenda.
 *
 * The ViewModel knows nothing about Android: the card turns [CardLine.date] and
 * [CardLine.time] into text, so a test drives the list without a `Context`.
 */
class NextSevenDaysViewModel(
    appointmentRepository: AppointmentRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val horizon: Duration = SEVEN_DAYS,
) : ViewModel() {

    val uiState: StateFlow<NextSevenDaysUiState> =
        appointmentRepository.observeUpcoming(from = clock())
            .map { entries -> stateFor(entries, clock()) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NextSevenDaysUiState(),
            )

    private fun stateFor(entries: List<AppointmentEntry>, now: Instant): NextSevenDaysUiState {
        val to = now.plus(horizon)
        val upcoming = entries
            .map { it to it.appointment.startInstant() }
            .filter { (entry, instant) ->
                !entry.appointment.isCancelled &&
                    !instant.isBefore(now) &&
                    !instant.isAfter(to)
            }
            .sortedWith(
                compareBy<Pair<AppointmentEntry, Instant>>(
                    { it.second },
                    { it.first.appointment.id.value },
                ),
            )
            .map { (entry, _) -> CardLine.of(entry) }

        return NextSevenDaysUiState(
            loaded = true,
            lines = upcoming.take(MAX_LINES),
            extraCount = (upcoming.size - MAX_LINES).coerceAtLeast(0),
        )
    }

    companion object {
        /** How many lines the card shows before it counts the rest. */
        const val MAX_LINES: Int = 5

        /** The card's window: the next seven days (D29). */
        val SEVEN_DAYS: Duration = Duration.ofDays(7)
    }
}

/** One appointment line of the card, in the shell's words. */
data class CardLine(
    val appointmentId: String,
    val patientId: PatientId,
    val patientName: String,
    val date: LocalDate,
    val time: LocalTime,
    val title: String?,
    val kind: AppointmentKind,
    /** Where it happens, when recorded ("Lab Joaquim Chaves"). */
    val place: String? = null,
    /** What the patient must do beforehand ("Fasting"), when recorded. */
    val preparation: String? = null,
) {
    companion object {
        internal fun of(entry: AppointmentEntry): CardLine = CardLine(
            appointmentId = entry.appointment.id.value,
            patientId = entry.appointment.patientId,
            patientName = entry.patientName,
            date = entry.appointment.localDate,
            time = entry.appointment.localTime,
            title = entry.appointment.title,
            kind = entry.appointment.kind,
            place = entry.appointment.place,
            preparation = entry.appointment.preparation,
        )
    }
}

/** What the card shows; [isVisible] is false when nothing is coming up. */
data class NextSevenDaysUiState(
    val loaded: Boolean = false,
    val lines: List<CardLine> = emptyList(),
    /** Appointments in the window beyond [NextSevenDaysViewModel.MAX_LINES]. */
    val extraCount: Int = 0,
) {
    val isVisible: Boolean get() = lines.isNotEmpty()
}
