package io.github.lgpy.familycare.feature.medications.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.domain.PushMessage
import io.github.lgpy.familycare.feature.medications.domain.PushMessages
import io.github.lgpy.familycare.feature.medications.reminders.DoseReminderScheduler
import io.github.lgpy.familycare.feature.medications.reminders.ReminderNotifications
import io.github.lgpy.familycare.feature.medications.reminders.ScheduledRemindersStore
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * The WorkManager side of push (M4).
 *
 * Two short jobs, both idempotent:
 * - **registration** — needs the network, so it is a constrained one-off that
 *   retries with backoff: a sign-in in airplane mode registers when the phone
 *   reconnects, even if the app was killed meanwhile (the brief's "retry later
 *   if offline").
 * - **clear** — local work only (database + notifications). A data-only,
 *   high-priority message wakes the app briefly; WorkManager gives that work a
 *   process that outlives `onMessageReceived` and survives the encrypted
 *   database's first init.
 *
 * Both reach Koin through [GlobalContext], like the reminder receivers: this is
 * the same process, started by the system with `FamilyCareApplication.onCreate`.
 */
object PushWork {

    private const val REGISTRATION_WORK = "push-register-device"
    private const val BACKOFF_SECONDS = 30L

    const val KEY_REGIMEN_ID = "regimen_id"
    const val KEY_SCHEDULED_LOCAL = "scheduled_local"

    /** Queues one registration attempt; a pending one is kept (it re-reads the token). */
    fun enqueueRegistration(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            REGISTRATION_WORK,
            // KEEP: the pending attempt reads the current session and token when
            // it runs, so it is equally correct for a new token or another user.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Queues the notification cancellation for one cleared slot. */
    fun enqueueClear(
        context: Context,
        message: PushMessage.Clear,
    ) {
        val request = OneTimeWorkRequestBuilder<PushClearWorker>()
            .setInputData(
                workDataOf(
                    KEY_REGIMEN_ID to message.regimenId,
                    KEY_SCHEDULED_LOCAL to PushMessages.formatScheduledLocal(
                        message.scheduledLocal,
                    ),
                ),
            )
            .build()

        // Every clear is kept: two slots can map to one group notification, and
        // cancelling is idempotent.
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }
}

/**
 * Registers this install's FCM token for the signed-in user.
 *
 * A retry is a transient failure (no network, server hiccup). Being signed out
 * is success: the next sign-in queues a fresh attempt.
 */
class PushRegistrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        val registered: kotlin.Result<Unit> = runCatching {
            koin.get<PushTokenRegistrar>().registerNow()
        }.getOrElse { error -> kotlin.Result.failure(error) }
        return if (registered.isSuccess) Result.success() else Result.retry()
    }
}

/**
 * Applies one `clear` message with the app in the background or killed.
 *
 * 1. Cancels the local dose-reminder notification of the group containing
 *    `(regimen_id, scheduled_local)`, through the slot index the reminder wrote
 *    when it posted (no database needed).
 * 2. Cancels the escalation notification for that patient and slot. Its tag has
 *    the **instant**, which the payload does not carry, so it is rebuilt from
 *    the local regimen (wall clock in the regimen's zone, the same rule the
 *    slot calculation and the server use).
 * 3. Reschedules the reminders, so a slot the local database already knows is
 *    confirmed loses its alarm too.
 *
 * Every step is best effort and independent: an unusable database must not
 * leave the dose-reminder notification behind, and a missing notification is
 * not a failure.
 */
class PushClearWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val regimenId = inputData.getString(PushWork.KEY_REGIMEN_ID) ?: return Result.success()
        val scheduledLocal = PushMessages.parseScheduledLocal(
            inputData.getString(PushWork.KEY_SCHEDULED_LOCAL),
        ) ?: return Result.success()

        val context = applicationContext

        runCatching {
            ReminderNotifications.cancelSlot(
                context = context,
                regimenId = regimenId,
                scheduledLocal = scheduledLocal,
                store = ScheduledRemindersStore(context),
            )
        }

        val koin = GlobalContext.getOrNull()
        if (koin != null) {
            runCatching { cancelEscalation(context, koin.get(), regimenId, scheduledLocal) }
            runCatching { koin.get<DoseReminderScheduler>().reschedule() }
        }

        return Result.success()
    }

    /** The escalation tag needs the patient and the slot's instant (see the class comment). */
    private suspend fun cancelEscalation(
        context: Context,
        medications: MedicationRepository,
        regimenId: String,
        scheduledLocal: LocalDateTime,
    ) {
        val active = medications.activeMedications.first()
        val medication = active.firstOrNull { entry ->
            entry.regimens.any { it.id.value == regimenId }
        } ?: return
        val regimen = medication.regimens.first { it.id.value == regimenId }

        val instant = scheduledLocal.atZone(regimen.timeZone).toInstant()
        PushNotifications.cancel(
            context,
            PushMessages.escalationTag(medication.medication.patientId.value, instant),
        )
    }
}
