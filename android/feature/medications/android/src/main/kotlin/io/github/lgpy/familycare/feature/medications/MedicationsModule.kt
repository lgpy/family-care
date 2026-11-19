package io.github.lgpy.familycare.feature.medications

import io.github.lgpy.familycare.feature.medications.dashboard.DosesTodayViewModel
import io.github.lgpy.familycare.feature.medications.dashboard.LowStockViewModel
import io.github.lgpy.familycare.feature.medications.dashboard.NeedsAttentionViewModel
import io.github.lgpy.familycare.feature.medications.dashboard.ReminderHealthViewModel
import io.github.lgpy.familycare.feature.medications.data.AssetMedicationCatalogRepository
import io.github.lgpy.familycare.feature.medications.data.CatalogTsv
import io.github.lgpy.familycare.feature.medications.data.MedicationCatalogRepository
import io.github.lgpy.familycare.feature.medications.detail.MedicationDetailViewModel
import io.github.lgpy.familycare.feature.medications.doses.DoseSlotViewModel
import io.github.lgpy.familycare.feature.medications.form.MedicationFormViewModel
import io.github.lgpy.familycare.feature.medications.list.PatientMedicationsViewModel
import io.github.lgpy.familycare.feature.medications.regimen.ChangeRegimenViewModel
import io.github.lgpy.familycare.feature.medications.shopping.ShoppingViewModel
import java.time.LocalDateTime
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The medications module's screen view models.
 *
 * The app includes this module in its graph (`modules(medicationsModule, …)`), so
 * the feature owns its view models and the app only names the module — D5: the
 * app wires modules, modules never depend on each other. Every dependency here
 * is a core or own-module interface, plus the repositories the app already
 * registers from [io.github.lgpy.familycare.feature.medications.data.MedicationsData].
 */
val medicationsModule: Module = module {

    // D31: the medication-name catalogue, read once from the APK's assets and
    // kept in memory. Registered here rather than in the app so the feature
    // owns everything its screens need.
    single<MedicationCatalogRepository> {
        AssetMedicationCatalogRepository(
            openAsset = { androidContext().assets.open(CatalogTsv.ASSET_NAME) },
        )
    }

    viewModel {
        DosesTodayViewModel(
            doseLogRepository = get(),
            medicationRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel {
        NeedsAttentionViewModel(
            doseLogRepository = get(),
            medicationRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel { ReminderHealthViewModel(checker = get()) }

    // M5/D28: the Today card that digests the low-stock list.
    viewModel { LowStockViewModel(stockRepository = get()) }

    viewModel { (patientId: String, scheduledLocal: String) ->
        DoseSlotViewModel(
            patientId = patientId,
            scheduledLocal = LocalDateTime.parse(scheduledLocal),
            doseLogRepository = get(),
            medicationRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel { (patientId: String) ->
        PatientMedicationsViewModel(
            patientId = patientId,
            medicationRepository = get(),
            patientRepository = get(),
            stockRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel { (patientId: String, medicationId: String) ->
        MedicationFormViewModel(
            patientId = patientId,
            medicationId = medicationId.ifBlank { null },
            medicationRepository = get(),
            stockRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
            catalogRepository = get(),
        )
    }

    viewModel { (medicationId: String) ->
        ChangeRegimenViewModel(
            medicationId = medicationId,
            medicationRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel { (medicationId: String) ->
        MedicationDetailViewModel(
            medicationId = medicationId,
            medicationRepository = get(),
            doseLogRepository = get(),
            stockRepository = get(),
            careCircleRepository = get(),
        )
    }

    viewModel {
        ShoppingViewModel(
            stockRepository = get(),
            medicationRepository = get(),
            careCircleRepository = get(),
        )
    }
}
