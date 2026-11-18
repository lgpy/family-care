package io.github.lgpy.familycare.feature.appointments.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canManage
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsResult
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentId
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentsUiError
import io.github.lgpy.familycare.feature.appointments.ui.uiError
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One appointment: every field, the actions a writer may take (edit, cancel,
 * schedule follow-up) and the owner-only delete (D29).
 *
 * The flags come from the signed-in role, the same rule the repository enforces;
 * the server remains the authority. A failed action is a
 * [AppointmentsUiError] in [AppointmentDetailUiState.actionError], never an
 * exception.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentDetailViewModel(
    private val appointmentId: String,
    private val appointmentRepository: AppointmentRepository,
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    private val appointment: Flow<Appointment?> =
        appointmentRepository.observe(AppointmentId(appointmentId))

    /** The patient's display name, re-watched when the appointment row changes. */
    private val patientName: Flow<String?> = appointment
        .flatMapLatest { current ->
            val patientId = current?.patientId
            if (patientId == null) {
                flowOf(null)
            } else {
                patientRepository.observe(patientId).map { it?.displayName }
            }
        }

    val uiState: StateFlow<AppointmentDetailUiState> = combine(
        appointment,
        patientName,
        careCircleRepository.members,
        careCircleRepository.myRole,
        local,
    ) { current, name, members, role, state ->
        AppointmentDetailUiState(
            loaded = true,
            appointment = current,
            patientName = name,
            companionName = current?.companionUserId?.let { companion ->
                members.firstOrNull { it.userId == companion }?.displayName
            },
            canWrite = role.canWrite,
            canDelete = role.canManage,
            cancelling = state.cancelling,
            deleting = state.deleting,
            actionError = state.actionError,
            deleted = state.deleted,
            now = clock(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppointmentDetailUiState(),
    )

    /** Cancels the appointment (stamped, not deleted). */
    fun cancel() = act(cancel = true)

    /** Deletes the appointment; the owner-only rule lives in the repository. */
    fun delete() = act(cancel = false)

    private fun act(cancel: Boolean) {
        if (local.value.cancelling || local.value.deleting) return
        local.update {
            it.copy(cancelling = cancel, deleting = !cancel, actionError = null)
        }
        viewModelScope.launch {
            val result = if (cancel) {
                appointmentRepository.cancel(AppointmentId(appointmentId))
            } else {
                appointmentRepository.delete(AppointmentId(appointmentId))
            }
            local.update { state ->
                when (result) {
                    is AppointmentsResult.Success -> state.copy(
                        cancelling = false,
                        deleting = false,
                        deleted = !cancel,
                    )

                    is AppointmentsResult.Failure -> state.copy(
                        cancelling = false,
                        deleting = false,
                        actionError = result.uiError(),
                    )
                }
            }
        }
    }

    private data class LocalState(
        val cancelling: Boolean = false,
        val deleting: Boolean = false,
        val actionError: AppointmentsUiError? = null,
        val deleted: Boolean = false,
    )
}

/** What the detail screen shows. */
data class AppointmentDetailUiState(
    val loaded: Boolean = false,
    val appointment: Appointment? = null,
    val patientName: String? = null,
    val companionName: String? = null,
    val canWrite: Boolean = false,
    val canDelete: Boolean = false,
    val cancelling: Boolean = false,
    val deleting: Boolean = false,
    val actionError: AppointmentsUiError? = null,
    val deleted: Boolean = false,
    val now: Instant = Instant.EPOCH,
) {

    /** Whether the user may edit, cancel and schedule a follow-up. */
    val canEdit: Boolean get() = canWrite && appointment != null && !appointment.isCancelled

    /** The appointment is past, in its own zone. */
    val isPast: Boolean get() = appointment?.isPast(now) ?: false

    /** The appointment is missing from the local database. */
    val missing: Boolean get() = loaded && appointment == null
}
