package io.github.lgpy.familycare.feature.appointments.data

import com.powersync.PowerSyncDatabase
import io.github.lgpy.familycare.core.data.sync.SyncedTables
import io.github.lgpy.familycare.core.domain.auth.AuthState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/**
 * The appointments module's data-layer entry point for the app's dependency
 * graph.
 *
 * Two things the app needs, and nothing else:
 *
 * 1. [syncedTables] — the `appointments` table and its upload rules, passed to
 *    `DataLayer.create(featureTables = …)`. This is the extension point that
 *    keeps `core:data` from depending on a feature.
 * 2. [appointmentRepository] — a factory for the module's repository, built from
 *    the local database the data layer exposes. The app registers it under the
 *    interface type, so screens inject [AppointmentRepository] and never see a
 *    PowerSync type.
 *
 * The parameters exist for tests: a fixed clock and a deterministic id
 * generator let an integration test assert exactly what is queued.
 */
object AppointmentsData {

    /** The synced tables this module contributes to the local schema. */
    val syncedTables: SyncedTables get() = AppointmentsSyncedTables

    /** [AppointmentRepository] over [database], for the app's Koin module. */
    fun appointmentRepository(
        database: PowerSyncDatabase,
        authState: StateFlow<AuthState>,
        now: () -> Instant = { Instant.now() },
        newId: () -> String = { UUID.randomUUID().toString() },
    ): AppointmentRepository = PowerSyncAppointmentRepository(
        database = database,
        authState = authState,
        now = now,
        newId = newId,
    )
}
