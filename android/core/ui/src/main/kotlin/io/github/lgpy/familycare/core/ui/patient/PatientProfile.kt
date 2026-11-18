package io.github.lgpy.familycare.core.ui.patient

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/*
 * The patient profile extension point (D29, docs/ux/screens.md §3).
 *
 * The app owns the screen and its History tab; a feature module contributes the
 * pieces that sit inside it, exactly as a module contributes a `DashboardCard`
 * to Today. The app shell therefore never imports a feature's screens (D5):
 * it draws whatever was registered, and a contribution opens its own routes
 * through the `onNavigate` callback.
 */

/**
 * A block shown under the patient's name, above the tabs: patient-info's
 * allergies banner and conditions today.
 *
 * [order] is the block's position, lower first. The shell leaves gaps between
 * the values it knows about (patient-info at 0) so a module can slot in without
 * renumbering.
 */
interface PatientHeader {

    /** Lower sorts first, directly under the patient's name. */
    val order: Int

    /**
     * The block's content. [onNavigate] asks the shell to open a route the
     * module owns, so a header can link to its own screens.
     */
    @Composable
    fun Content(patientId: PatientId, onNavigate: (String) -> Unit)
}

/**
 * One tab of the patient profile, contributed by a feature module.
 *
 * The app adds its own **History** tab after every contributed tab, so
 * [order] only orders the feature tabs. [id] is stable and identifies the tab
 * while the screen is alive; the registry rejects two tabs with the same id.
 */
interface PatientTab {

    /** Stable id of the tab; unique across the contributions. */
    val id: String

    /** Lower sorts first. */
    val order: Int

    /** The tab's label. */
    @get:StringRes
    val titleRes: Int

    /** The tab's content, drawn below the tab row. */
    @Composable
    fun Content(patientId: PatientId, onNavigate: (String) -> Unit)
}

/** What an entry of the History tab is, so the shell can pick its icon. */
enum class TimelineKind {
    MEDICATION,
    APPOINTMENT,
    OTHER,
}

/**
 * One event of a patient's history, already localized by the module that knows
 * it (a regimen change, an appointment, a measurement).
 *
 * @param at When it happened; the shell merges every source and sorts newest
 *   first.
 * @param title The one-line event ("Started Metformin 500 mg").
 * @param detail Optional second line; null when the title says everything.
 * @param route The module route to open on tap, or null when the event has no
 *   screen of its own.
 */
data class TimelineEntry(
    val id: String,
    val at: Instant,
    val title: String,
    val detail: String?,
    val kind: TimelineKind,
    val route: String?,
)

/**
 * A module's contribution to the patient's History tab: every event it knows
 * for [patientId], as a flow that re-emits when the local data changes.
 *
 * A source returns already-localized text. It is constructed by the app's
 * dependency graph with a [TimelineText] (below) rather than a `Context`, so a
 * module can build its strings with `getString(...)` in production and still
 * be unit-tested on the JVM against a fake that needs no Android `Resources`.
 */
interface TimelineSource {

    /** This module's events for [patientId], in any order. */
    fun entries(patientId: PatientId): Flow<List<TimelineEntry>>
}

/**
 * The string lookup a [TimelineSource] uses, resolved by the app from
 * `Context.resources`.
 *
 * This is deliberately not `Resources.getString` directly: a unit test cannot
 * build a real `Resources` without Robolectric, so the source takes this small
 * seam instead and the test supplies a fake.
 */
fun interface TimelineText {

    /** `resources.getString(id, *args)`, already localized. */
    fun get(@StringRes id: Int, vararg args: Any?): String
}

/** The production [TimelineText]: the app's `Resources`, in the user's language. */
class ResourcesTimelineText(private val resources: Resources) : TimelineText {

    constructor(context: Context) : this(context.resources)

    override fun get(id: Int, vararg args: Any?): String = resources.getString(id, *args)
}

/**
 * Every header the shell should render, collected once from Koin's `getAll()`.
 *
 * A wrapper type (rather than injecting `List<PatientHeader>`) keeps the
 * multibinding in the dependency graph and the shell free of Koin generics,
 * matching `DashboardCards`.
 */
class PatientHeaders(headers: List<PatientHeader>) {

    /** The headers, topmost first; equal orders keep registration order. */
    val ordered: List<PatientHeader> = headers.sortedBy { it.order }
}

/**
 * Every contributed tab, in order.
 *
 * Two tabs with the same [PatientTab.id] are a wiring mistake (two modules
 * claiming the same tab), so the registry refuses them when it is built at
 * start-up rather than showing one tab over the other.
 */
class PatientTabs(tabs: List<PatientTab>) {

    init {
        val duplicates = tabs.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Patient tab ids must be unique; registered twice: ${duplicates.joinToString()}"
        }
    }

    /** The tabs, in display order; equal orders keep registration order. */
    val ordered: List<PatientTab> = tabs.sortedBy { it.order }
}

/**
 * Every module's history source, merged into one list.
 *
 * [entries] combines the sources, so any module's local write refreshes the
 * History tab; the result is newest first and the shell groups it by month.
 * A source that fails is left to the caller's coroutine scope: the profile's
 * view model owns the collection.
 */
class TimelineSources(val sources: List<TimelineSource>) {

    /** All sources' events for [patientId], newest first. */
    fun entries(patientId: PatientId): Flow<List<TimelineEntry>> {
        if (sources.isEmpty()) return flowOf(emptyList())
        val flows = sources.map { it.entries(patientId) }
        return combine(flows) { perSource ->
            perSource.flatMap { it }.sortedByDescending { entry -> entry.at }
        }
    }
}
