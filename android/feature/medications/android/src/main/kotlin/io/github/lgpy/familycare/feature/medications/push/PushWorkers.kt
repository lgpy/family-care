package io.github.lgpy.familycare.feature.medications.push

import android.content.Context
import androidx.work.CoroutineWorker
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
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * The WorkManager side of the medications `clear` message (M4; D29 keeps it in
 * the feature, because it needs the local database and reminders).
 *
 * A data-only, high-priority message wakes the app briefly; WorkManager gives
 * that work a process that outlives `onMessageReceived` and survives the
 * encrypted database's first init. It reaches Koin through [GlobalContext], like
 * the reminder receivers: this is the same process, started by the system with
 * `FamilyCareApplication.onCreate`.
 */
object PushWork {

    const val KEY_REGIMEN_ID = "regimen_id"
    const val KEY_SCHEDULED_LOCAL = "scheduled_local"

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
