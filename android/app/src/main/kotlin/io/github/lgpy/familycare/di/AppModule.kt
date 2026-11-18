package io.github.lgpy.familycare.di

import android.os.SystemClock
import io.github.lgpy.familycare.BuildConfig
import io.github.lgpy.familycare.auth.GoogleSignIn
import io.github.lgpy.familycare.core.data.DataLayer
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCards
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockPolicy
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import io.github.lgpy.familycare.feature.medications.dashboard.MedicationsDashboardCard
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationsData
import io.github.lgpy.familycare.feature.medications.medicationsModule
import io.github.lgpy.familycare.ui.MoreViewModel
import io.github.lgpy.familycare.ui.SignInViewModel
import io.github.lgpy.familycare.ui.TodayViewModel
import io.github.lgpy.familycare.ui.circle.CircleGateViewModel
import io.github.lgpy.familycare.ui.circle.FamilyCircleViewModel
import io.github.lgpy.familycare.ui.patients.PatientFormViewModel
import io.github.lgpy.familycare.ui.patients.PatientsViewModel
import io.github.lgpy.familycare.ui.profile.YourNameViewModel
import io.github.lgpy.familycare.ui.sync.UploadRejectionViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The app's dependency graph: the app lock, language state, the Supabase
 * session, sync and the core repositories (care circle, patients, profile),
 * plus the M2 screen view models.
 *
 * Environment values come from BuildConfig (see android/environments). The
 * Supabase client, the encrypted local database, the PowerSync connector and
 * the repository implementations are built inside core/data, so this module
 * never sees those types. Screens inject the domain interfaces:
 * `koinInject<CareCircleRepository>()`, `koinInject<PatientRepository>()`,
 * `koinInject<ProfileRepository>()`.
 */
val appModule: Module = module {

    // Monotonic clock: elapsedRealtime is unaffected by wall-clock changes.
    single { AppLockPolicy(elapsedRealtime = SystemClock::elapsedRealtime) }
    single { AppLockController(context = androidContext(), policy = get()) }

    single { AppLanguageController(context = androidContext()) }

    // D12: Supabase session, plus the sync layer (one Supabase client and one
    // encrypted PowerSync database for the process). The publishable key is
    // public by design; RLS protects the data (security-and-privacy §3).
    //
    // Feature modules contribute their synced tables here (D5): the medications
    // module adds `medications`, `regimens` and `dose_log` with their upload
    // rules, and core/data merges them with the core's own tables.
    single {
        DataLayer.create(
            context = androidContext(),
            supabaseUrl = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            powerSyncUrl = BuildConfig.POWERSYNC_URL,
            featureTables = listOf(MedicationsData.syncedTables),
        )
    }
    single<AuthRepository> { get<DataLayer>().authRepository }
    single<SyncService> { get<DataLayer>().sync }

    // M2 care circle. Reads are local and offline-first; membership changes go
    // through the core RPCs.
    single<CareCircleRepository> { get<DataLayer>().careCircleRepository }
    single<PatientRepository> { get<DataLayer>().patientRepository }
    single<ProfileRepository> { get<DataLayer>().profileRepository }

    // M3 medications. The module builds its repositories from the same local
    // database; screens inject only the interfaces.
    single<MedicationRepository> {
        val data = get<DataLayer>()
        MedicationsData.medicationRepository(data.database, data.authState)
    }
    single<DoseLogRepository> {
        val data = get<DataLayer>()
        MedicationsData.doseLogRepository(data.database, data.authState)
    }

    // The dashboard extension point (architecture.md §5): a feature contributes a
    // card, the shell renders whatever was registered, in order. Today imports no
    // feature module to do it.
    single<DashboardCard> { MedicationsDashboardCard() }
    single { DashboardCards(getAll<DashboardCard>()) }

    // The medications module's view models. Included here so the app stays the
    // only place that knows which modules exist.
    includes(medicationsModule)

    // Needs an Activity at call time, so it is a plain collaborator rather than
    // a singleton holding one.
    single { GoogleSignIn(webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID) }

    viewModel { SignInViewModel(get()) }

    // M2 screens. Each view model exposes a StateFlow of UI state and takes the
    // domain interfaces, so the unit tests run against fakes.
    viewModel {
        CircleGateViewModel(
            careCircleRepository = get(),
            authRepository = get(),
            syncState = get<SyncService>().state,
        )
    }
    viewModel { TodayViewModel(get(), get(), get()) }
    viewModel { PatientsViewModel(patientRepository = get(), careCircleRepository = get()) }
    viewModel { (patientId: String) ->
        PatientFormViewModel(
            patientId = patientId.ifBlank { null },
            patientRepository = get(),
            careCircleRepository = get(),
        )
    }
    viewModel { FamilyCircleViewModel(careCircleRepository = get()) }
    viewModel { YourNameViewModel(profileRepository = get()) }
    viewModel { MoreViewModel(profileRepository = get()) }

    // App shell: the message about a change the server refused. It only needs
    // the sync state, which is a domain type.
    viewModel { UploadRejectionViewModel(syncService = get()) }
}
