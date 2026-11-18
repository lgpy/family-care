package io.github.lgpy.familycare.di

import android.os.SystemClock
import android.app.AlarmManager
import io.github.lgpy.familycare.BuildConfig
import io.github.lgpy.familycare.auth.GoogleSignIn
import io.github.lgpy.familycare.auth.PushAwareAuthRepository
import io.github.lgpy.familycare.core.data.DataLayer
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.github.lgpy.familycare.core.domain.push.PushDeviceRepository
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCards
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockPolicy
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import io.github.lgpy.familycare.feature.medications.dashboard.LowStockDashboardCard
import io.github.lgpy.familycare.feature.medications.dashboard.MedicationsDashboardCard
import io.github.lgpy.familycare.feature.medications.dashboard.NeedsAttentionDashboardCard
import io.github.lgpy.familycare.feature.medications.dashboard.ReminderHealthDashboardCard
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationsData
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.medicationsModule
import io.github.lgpy.familycare.feature.medications.push.FcmTokenSource
import io.github.lgpy.familycare.feature.medications.push.FirebaseTokenSource
import io.github.lgpy.familycare.feature.medications.push.PushRegistrationStore
import io.github.lgpy.familycare.feature.medications.push.PushSession
import io.github.lgpy.familycare.feature.medications.push.PushTokenRegistrar
import io.github.lgpy.familycare.feature.medications.push.SharedPreferencesPushRegistrationStore
import io.github.lgpy.familycare.feature.medications.reminders.DoseReminderScheduler
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealthChecker
import io.github.lgpy.familycare.feature.medications.reminders.ReminderSchedulerCoordinator
import io.github.lgpy.familycare.feature.medications.reminders.ScheduledRemindersStore
import io.github.lgpy.familycare.ui.MoreViewModel
import io.github.lgpy.familycare.ui.SignInViewModel
import io.github.lgpy.familycare.ui.TodayViewModel
import io.github.lgpy.familycare.ui.circle.CircleGateViewModel
import io.github.lgpy.familycare.ui.circle.FamilyCircleViewModel
import io.github.lgpy.familycare.ui.patients.PatientFormViewModel
import io.github.lgpy.familycare.ui.patients.PatientsViewModel
import io.github.lgpy.familycare.ui.profile.YourNameViewModel
import io.github.lgpy.familycare.ui.sync.UploadRejectionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    single { DataLayer.create(
            context = androidContext(),
            supabaseUrl = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            powerSyncUrl = BuildConfig.POWERSYNC_URL,
            featureTables = listOf(MedicationsData.syncedTables),
        )
    }
    single<AuthRepository> {
        // M4/D27: every sign-out unregisters this install's push token before
        // the session is cleared, whichever screen started it.
        PushAwareAuthRepository(
            delegate = get<DataLayer>().authRepository,
            push = get(),
        )
    }
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
    // M5/D28: the stock ledger derives its summaries from the medication and the
    // dose log, so it reuses the two repositories above.
    single<StockRepository> {
        val data = get<DataLayer>()
        MedicationsData.stockRepository(
            database = data.database,
            authState = data.authState,
            medicationRepository = get(),
            doseLogRepository = get(),
        )
    }

    // The dashboard extension point (architecture.md §5): a feature contributes a
    // card, the shell renders whatever was registered, in order. Today imports no
    // feature module to do it.
    single<DashboardCard> { MedicationsDashboardCard() }
    single<DashboardCard> { NeedsAttentionDashboardCard() }
    single<DashboardCard> { LowStockDashboardCard() }
    single<DashboardCard> { ReminderHealthDashboardCard() }
    single { DashboardCards(getAll<DashboardCard>()) }

    // M4 dose reminders (D4/D19/D25). The scheduler, its local alarm index and
    // the health check are app-wide singletons: receivers reach them through
    // Koin after `FamilyCareApplication` has started it.
    single { ScheduledRemindersStore(androidContext()) }
    single {
        DoseReminderScheduler(
            context = androidContext(),
            medicationRepository = get(),
            doseLogRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
            authRepository = get(),
            store = get(),
            alarmManager = androidContext().getSystemService(AlarmManager::class.java),
        )
    }
    single { ReminderHealthChecker(androidContext(), get(), get(), get()) }
    single {
        ReminderSchedulerCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            scheduler = get(),
            medicationRepository = get(),
            doseLogRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
            authRepository = get(),
        )
    }

    // The medications module's view models. Included here so the app stays the
    // only place that knows which modules exist.
    includes(medicationsModule)

    // M4 push (D4/D27): the device registration that makes escalation and clear
    // reach this phone. The token is fetched by Firebase and never logged; the
    // registration is retried by WorkManager while offline, and the session
    // (above) is what triggers it.
    single<PushDeviceRepository> { get<DataLayer>().pushDeviceRepository }
    single<PushRegistrationStore> { SharedPreferencesPushRegistrationStore(androidContext()) }
    single<FcmTokenSource> { FirebaseTokenSource() }
    single {
        PushTokenRegistrar(
            repository = get(),
            authState = get<DataLayer>().authState,
            tokens = get(),
            store = get(),
        )
    }
    single {
        PushSession(
            context = androidContext(),
            registrar = get(),
            authState = get<DataLayer>().authState,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

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
