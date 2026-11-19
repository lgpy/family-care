package io.github.lgpy.familycare.di

import android.os.SystemClock
import android.app.AlarmManager
import io.github.lgpy.familycare.BuildConfig
import io.github.lgpy.familycare.auth.GoogleSignIn
import io.github.lgpy.familycare.auth.PushAwareAuthRepository
import io.github.lgpy.familycare.core.data.DataLayer
import io.github.lgpy.familycare.core.data.activity.ActivityRepository
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.github.lgpy.familycare.core.domain.push.PushDeviceRepository
import io.github.lgpy.familycare.core.notifications.FcmTokenSource
import io.github.lgpy.familycare.core.notifications.FirebaseTokenSource
import io.github.lgpy.familycare.core.notifications.PushHandler
import io.github.lgpy.familycare.core.notifications.PushHandlers
import io.github.lgpy.familycare.core.notifications.PushRegistrationStore
import io.github.lgpy.familycare.core.notifications.PushSession
import io.github.lgpy.familycare.core.notifications.PushTokenRegistrar
import io.github.lgpy.familycare.core.notifications.SharedPreferencesPushRegistrationStore
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCard
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCards
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockPolicy
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import io.github.lgpy.familycare.core.ui.patient.PatientHeader
import io.github.lgpy.familycare.core.ui.patient.PatientHeaders
import io.github.lgpy.familycare.core.ui.patient.PatientTab
import io.github.lgpy.familycare.core.ui.patient.PatientTabs
import io.github.lgpy.familycare.core.ui.patient.ResourcesTimelineText
import io.github.lgpy.familycare.core.ui.patient.TimelineSource
import io.github.lgpy.familycare.core.ui.patient.TimelineSources
import io.github.lgpy.familycare.core.ui.patient.TimelineText
import io.github.lgpy.familycare.feature.appointments.appointmentsModule
import io.github.lgpy.familycare.feature.appointments.dashboard.NextSevenDaysDashboardCard
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsData
import io.github.lgpy.familycare.feature.appointments.profile.AppointmentsPatientTab
import io.github.lgpy.familycare.feature.appointments.profile.AppointmentsTimelineSource
import io.github.lgpy.familycare.feature.appointments.push.AppointmentsPushHandler
import io.github.lgpy.familycare.feature.appointments.reminders.AppointmentReminderCoordinator
import io.github.lgpy.familycare.feature.appointments.reminders.AppointmentReminderScheduler
import io.github.lgpy.familycare.feature.appointments.reminders.AppointmentRemindersStore
import io.github.lgpy.familycare.feature.medications.dashboard.LowStockDashboardCard
import io.github.lgpy.familycare.feature.medications.dashboard.MedicationsDashboardCard
import io.github.lgpy.familycare.feature.medications.dashboard.NeedsAttentionDashboardCard
import io.github.lgpy.familycare.feature.medications.dashboard.ReminderHealthDashboardCard
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationsData
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.medicationsModule
import io.github.lgpy.familycare.feature.medications.profile.MedicationsPatientTab
import io.github.lgpy.familycare.feature.medications.profile.MedicationsTimelineSource
import io.github.lgpy.familycare.feature.medications.push.MedicationsPushHandler
import io.github.lgpy.familycare.feature.medications.reminders.DoseReminderScheduler
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealthChecker
import io.github.lgpy.familycare.feature.medications.reminders.ReminderSchedulerCoordinator
import io.github.lgpy.familycare.feature.medications.reminders.ScheduledRemindersStore
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoData
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.patientInfoModule
import io.github.lgpy.familycare.feature.patientinfo.profile.AllergiesPatientHeader
import io.github.lgpy.familycare.feature.patientinfo.profile.InfoPatientTab
import io.github.lgpy.familycare.ui.MoreViewModel
import io.github.lgpy.familycare.ui.SignInViewModel
import io.github.lgpy.familycare.ui.TodayViewModel
import io.github.lgpy.familycare.ui.activity.ActivityViewModel
import io.github.lgpy.familycare.ui.circle.CircleGateViewModel
import io.github.lgpy.familycare.ui.circle.FamilyCircleViewModel
import io.github.lgpy.familycare.ui.patients.PatientFormViewModel
import io.github.lgpy.familycare.ui.patients.PatientProfileViewModel
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
    // rules, patient-info adds `info.patient_info`, and core/data merges them
    // with the core's own tables.
    single { DataLayer.create(
            context = androidContext(),
            supabaseUrl = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            powerSyncUrl = BuildConfig.POWERSYNC_URL,
            featureTables = listOf(
                MedicationsData.syncedTables,
                PatientInfoData.syncedTables,
                AppointmentsData.syncedTables,
            ),
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
    // D30: the owner-only activity feed; online, because the audit log is not synced.
    single<ActivityRepository> { get<DataLayer>().activityRepository }

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

    // M6 patient info. One row per patient (`id = patient_id`), read by the
    // profile's allergies banner and Info tab and written by its edit form.
    single<PatientInfoRepository> {
        val data = get<DataLayer>()
        PatientInfoData.repository(data.database, data.authState)
    }
    // M6 appointments (D29): its repository over the same local database.
    single<AppointmentRepository> {
        val data = get<DataLayer>()
        AppointmentsData.appointmentRepository(data.database, data.authState)
    }

    // The dashboard extension point (architecture.md §5): a feature contributes a
    // card, the shell renders whatever was registered, in order. Today imports no
    // feature module to do it.
    single<DashboardCard> { MedicationsDashboardCard() }
    single<DashboardCard> { NeedsAttentionDashboardCard() }
    single<DashboardCard> { LowStockDashboardCard() }
    single<DashboardCard> { ReminderHealthDashboardCard() }
    // M6: upcoming appointments across patients in the next seven days.
    single<DashboardCard> { NextSevenDaysDashboardCard() }
    single { DashboardCards(getAll<DashboardCard>()) }

    // The patient profile extension point (D29, docs/ux/screens.md §3). The app
    // hosts the screen and its History tab; a feature contributes the pieces
    // inside it, exactly like a DashboardCard. Patient-info registers the
    // allergies banner and its Info tab; medications registers its tab and its
    // timeline source. Registries are built from getAll() so the shell names no
    // feature module (D5).
    single<TimelineText> { ResourcesTimelineText(androidContext()) }
    single<PatientHeader> { AllergiesPatientHeader() }
    single<PatientTab> { MedicationsPatientTab() }
    single<PatientTab> { InfoPatientTab() }
    single<PatientTab> { AppointmentsPatientTab() }
    single<TimelineSource> {
        MedicationsTimelineSource(medicationRepository = get(), text = get())
    }
    single<TimelineSource> {
        AppointmentsTimelineSource(appointmentRepository = get(), text = get())
    }
    single { PatientHeaders(getAll<PatientHeader>()) }
    single { PatientTabs(getAll<PatientTab>()) }
    single { TimelineSources(getAll<TimelineSource>()) }

    // The push extension point (D29): a feature claims the message `type`s it
    // owns, and the core's single FCM service routes each payload by `type`. The
    // registry rejects a type claimed twice when it is built (at start-up).
    single<PushHandler> { MedicationsPushHandler() }
    // M6 appointments (D29): the change push the server sends to owners and
    // caregivers when an appointment is added or changed.
    single<PushHandler> { AppointmentsPushHandler() }
    single { PushHandlers(getAll<PushHandler>()) }

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

    // M6 appointment reminders (docs/domain/appointments.md "Reminders"): the
    // same device-local technique as the dose reminders, written in the
    // appointments module because D5 forbids importing another feature. The
    // scheduler, its alarm index and its coordinator are app-wide singletons:
    // receivers reach them through Koin after `FamilyCareApplication` has
    // started it.
    single { AppointmentRemindersStore(androidContext()) }
    single {
        AppointmentReminderScheduler(
            context = androidContext(),
            appointmentRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
            authRepository = get(),
            store = get(),
            alarmManager = androidContext().getSystemService(AlarmManager::class.java),
        )
    }
    single {
        AppointmentReminderCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            scheduler = get(),
            appointmentRepository = get(),
            patientRepository = get(),
            careCircleRepository = get(),
            authRepository = get(),
        )
    }

    // The medications module's view models. Included here so the app stays the
    // only place that knows which modules exist.
    includes(medicationsModule)
    includes(appointmentsModule)

    // The patient-info module's view models (the profile banner and Info tab,
    // and the edit form).
    includes(patientInfoModule)

    // M4 push (D4/D27, D29): the device registration that makes pushes reach
    // this phone. The types and the jobs live in :core:notifications. The token
    // is fetched by Firebase and never logged; the registration is retried by
    // WorkManager while offline, and the session (above) is what triggers it.
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
    viewModel { (patientId: String) ->
        PatientProfileViewModel(
            patientId = patientId,
            patientRepository = get(),
            careCircleRepository = get(),
            timelineSources = get(),
        )
    }
    viewModel { FamilyCircleViewModel(careCircleRepository = get()) }
    viewModel { YourNameViewModel(profileRepository = get()) }
    viewModel { MoreViewModel(profileRepository = get()) }
    viewModel { ActivityViewModel(activityRepository = get(), careCircleRepository = get()) }

    // App shell: the message about a change the server refused. It only needs
    // the sync state, which is a domain type.
    viewModel { UploadRejectionViewModel(syncService = get()) }
}
