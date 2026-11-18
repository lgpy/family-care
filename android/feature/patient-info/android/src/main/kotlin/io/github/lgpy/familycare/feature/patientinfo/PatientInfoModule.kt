package io.github.lgpy.familycare.feature.patientinfo

import io.github.lgpy.familycare.feature.patientinfo.edit.PatientInfoEditViewModel
import io.github.lgpy.familycare.feature.patientinfo.profile.PatientInfoViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The patient-info module's screen view models.
 *
 * The app includes this module in its graph (`modules(patientInfoModule, …)`),
 * so the feature owns its view models and the app only names the module — D5:
 * the app wires modules, modules never depend on each other. Every dependency
 * here is a core or own-module interface, plus the repository the app registers
 * from [io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoData].
 */
val patientInfoModule: Module = module {

    viewModel { (patientId: String) ->
        PatientInfoViewModel(
            patientId = patientId,
            patientInfoRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel { (patientId: String) ->
        PatientInfoEditViewModel(
            patientId = patientId,
            patientInfoRepository = get(),
            careCircleRepository = get(),
        )
    }
}
