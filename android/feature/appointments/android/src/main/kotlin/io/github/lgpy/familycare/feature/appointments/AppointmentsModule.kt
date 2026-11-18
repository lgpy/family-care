package io.github.lgpy.familycare.feature.appointments

import io.github.lgpy.familycare.feature.appointments.agenda.AgendaViewModel
import io.github.lgpy.familycare.feature.appointments.detail.AppointmentDetailViewModel
import io.github.lgpy.familycare.feature.appointments.form.AppointmentFormViewModel
import io.github.lgpy.familycare.feature.appointments.profile.PatientAppointmentsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The appointments module's screen view models.
 *
 * The app includes this module in its graph, so the feature owns its view models
 * and the app only names the module — D5: the app wires modules, modules never
 * depend on each other. Every dependency here is a core or own-module interface,
 * plus the repository the app registers from
 * [io.github.lgpy.familycare.feature.appointments.data.AppointmentsData].
 */
val appointmentsModule: Module = module {

    viewModel {
        AgendaViewModel(
            appointmentRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel { (patientId: String) ->
        PatientAppointmentsViewModel(
            patientId = patientId,
            appointmentRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel { (patientId: String, appointmentId: String, followUpOf: String) ->
        AppointmentFormViewModel(
            patientIdArg = patientId.ifBlank { null },
            appointmentId = appointmentId.ifBlank { null },
            followUpOfArg = followUpOf.ifBlank { null },
            appointmentRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel { (appointmentId: String) ->
        AppointmentDetailViewModel(
            appointmentId = appointmentId,
            appointmentRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
        )
    }
}
