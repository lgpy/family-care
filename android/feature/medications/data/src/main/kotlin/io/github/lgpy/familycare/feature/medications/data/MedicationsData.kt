package io.github.lgpy.familycare.feature.medications.data

import com.powersync.PowerSyncDatabase
import io.github.lgpy.familycare.core.data.sync.SyncedTables
import io.github.lgpy.familycare.core.domain.auth.AuthState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The medications module's data-layer entry point for the app's dependency
 * graph.
 *
 * Two things the app needs, and nothing else:
 *
 * 1. [syncedTables] — the `medications`, `regimens` and `dose_log` tables and
 *    their upload rules, passed to `DataLayer.create(featureTables = …)`. This
 *    is the extension point that keeps `core:data` from depending on a feature.
 * 2. Factories for the module's repositories, built from the local database the
 *    data layer exposes. The app registers them under the interface types, so
 *    screens inject [MedicationRepository] and [DoseLogRepository] and never see
 *    a PowerSync type.
 *
 * Both repositories share the defaults ([Instant.now], UUID ids, a per-minute
 * tick); the parameters exist for tests and for a future screen that needs a
 * different tick.
 */
object MedicationsData {

    /** The synced tables this module contributes to the local schema. */
    val syncedTables: SyncedTables get() = MedicationsSyncedTables

    /** [MedicationRepository] over [database], for the app's Koin module. */
    fun medicationRepository(
        database: PowerSyncDatabase,
        authState: StateFlow<AuthState>,
        now: () -> Instant = { Instant.now() },
        newId: () -> String = { UUID.randomUUID().toString() },
    ): MedicationRepository = PowerSyncMedicationRepository(
        database = database,
        authState = authState,
        now = now,
        newId = newId,
    )

    /** [DoseLogRepository] over [database], for the app's Koin module. */
    fun doseLogRepository(
        database: PowerSyncDatabase,
        authState: StateFlow<AuthState>,
        now: () -> Instant = { Instant.now() },
        ticks: Flow<Instant> = minuteTicks(now),
        newId: () -> String = { UUID.randomUUID().toString() },
    ): DoseLogRepository = PowerSyncDoseLogRepository(
        database = database,
        authState = authState,
        now = now,
        ticks = ticks,
        newId = newId,
    )
}
