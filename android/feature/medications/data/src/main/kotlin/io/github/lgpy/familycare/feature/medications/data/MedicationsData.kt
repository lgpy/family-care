package io.github.lgpy.familycare.feature.medications.data

import com.powersync.PowerSyncDatabase
import io.github.lgpy.familycare.core.data.sync.SyncedTables
import io.github.lgpy.familycare.core.domain.auth.AuthState
import java.time.Clock
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
 * 1. [syncedTables] — the `medications`, `regimens`, `dose_log` and
 *    `stock_movements` tables and their upload rules, passed to
 *    `DataLayer.create(featureTables = …)`. This
 *    is the extension point that keeps `core:data` from depending on a feature.
 * 2. Factories for the module's repositories, built from the local database the
 *    data layer exposes. The app registers them under the interface types, so
 *    screens inject [MedicationRepository], [DoseLogRepository] and
 *    [StockRepository] and never see a PowerSync type.
 *
 * The repositories share the defaults ([Instant.now], UUID ids, a per-minute
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

    /**
     * [StockRepository] over [database], for the app's Koin module (M5).
     *
     * The derived summaries need the medications and their dose log, so this
     * takes the two repositories rather than reading those tables on its own.
     * [clock] decides both the movements' timestamps and "today"; [ticks]
     * re-derives days remaining as time passes.
     */
    fun stockRepository(
        database: PowerSyncDatabase,
        authState: StateFlow<AuthState>,
        medicationRepository: MedicationRepository,
        doseLogRepository: DoseLogRepository,
        clock: Clock = Clock.systemDefaultZone(),
        ticks: Flow<Instant> = minuteTicks { clock.instant() },
        newId: () -> String = { UUID.randomUUID().toString() },
    ): StockRepository = PowerSyncStockRepository(
        database = database,
        authState = authState,
        medicationRepository = medicationRepository,
        doseLogRepository = doseLogRepository,
        clock = clock,
        ticks = ticks,
        newId = newId,
    )
}
