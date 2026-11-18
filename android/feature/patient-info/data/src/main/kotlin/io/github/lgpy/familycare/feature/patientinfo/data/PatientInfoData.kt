package io.github.lgpy.familycare.feature.patientinfo.data

import com.powersync.PowerSyncDatabase
import io.github.lgpy.familycare.core.data.sync.SyncedTables
import io.github.lgpy.familycare.core.domain.auth.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * The patient-info module's data-layer entry point for the app's dependency
 * graph.
 *
 * Two things the app needs, and nothing else:
 *
 * 1. [syncedTables] — the `info.patient_info` table and its upload rules, passed
 *    to `DataLayer.create(featureTables = …)`. This is the extension point that
 *    keeps `core:data` from depending on a feature.
 * 2. [repository] — the module's repository over the local database the data
 *    layer exposes. The app registers it under the interface type, so screens
 *    inject [PatientInfoRepository] and never see a PowerSync type.
 */
object PatientInfoData {

    /** The synced tables this module contributes to the local schema. */
    val syncedTables: SyncedTables get() = PatientInfoSyncedTables

    /** [PatientInfoRepository] over [database], for the app's Koin module. */
    fun repository(
        database: PowerSyncDatabase,
        authState: StateFlow<AuthState>,
    ): PatientInfoRepository = PowerSyncPatientInfoRepository(
        database = database,
        authState = authState,
    )
}
