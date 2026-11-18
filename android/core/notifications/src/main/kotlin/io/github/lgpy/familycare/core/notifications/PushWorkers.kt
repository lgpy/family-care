package io.github.lgpy.familycare.core.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.context.GlobalContext

/**
 * The WorkManager side of FCM token registration (M4; D27).
 *
 * One short, idempotent job: registration needs the network, so it is a
 * constrained one-off that retries with backoff — a sign-in in airplane mode
 * registers when the phone reconnects, even if the app was killed meanwhile
 * (the brief's "retry later if offline").
 *
 * It reaches Koin through [GlobalContext], like the reminder receivers: this is
 * the same process, started by the system with `FamilyCareApplication.onCreate`.
 */
object PushWork {

    private const val REGISTRATION_WORK = "push-register-device"
    private const val BACKOFF_SECONDS = 30L

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
