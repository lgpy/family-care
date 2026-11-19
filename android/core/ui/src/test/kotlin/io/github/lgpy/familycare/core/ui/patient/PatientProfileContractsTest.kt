package io.github.lgpy.familycare.core.ui.patient

import androidx.compose.runtime.Composable
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The patient profile contracts: ordering, duplicate tabs, merged history. */
class PatientProfileContractsTest {

    private val patient = PatientId("patient-1")

    private fun header(order: Int): PatientHeader = object : PatientHeader {
        override val order: Int = order

        @Composable
        override fun Content(patientId: PatientId, onNavigate: (String) -> Unit) = Unit
    }

    private fun tab(id: String, order: Int): PatientTab = object : PatientTab {
        override val id: String = id
        override val order: Int = order
        override val titleRes: Int = 1

        @Composable
        override fun Content(patientId: PatientId, onNavigate: (String) -> Unit) = Unit
    }

    private fun entry(id: String, at: Instant): TimelineEntry = TimelineEntry(
        id = id,
        at = at,
        title = id,
        detail = null,
        kind = TimelineKind.OTHER,
        route = null,
    )

    @Test
    fun headersAreOrderedAndTiesKeepRegistrationOrder() {
        val second = header(order = 20)
        val first = header(order = 10)
        val tied = header(order = 10)

        assertEquals(listOf(first, tied, second), PatientHeaders(listOf(second, first, tied)).ordered)
    }

    @Test
    fun tabsAreOrderedAndTiesKeepRegistrationOrder() {
        val medications = tab(id = "medications", order = 10)
        val info = tab(id = "info", order = 20)
        val appointments = tab(id = "appointments", order = 20)

        assertEquals(
            listOf(medications, info, appointments),
            PatientTabs(listOf(info, medications, appointments)).ordered,
        )
    }

    @Test
    fun twoTabsWithTheSameIdAreRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PatientTabs(listOf(tab(id = "medications", order = 10), tab(id = "medications", order = 20)))
        }

        assertTrue(error.message.orEmpty().contains("medications"))
    }

    @Test
    fun aTabWithoutAnIconDefaultsToEvent() {
        assertEquals(PatientTabIcon.EVENT, tab(id = "info", order = 10).icon)
    }

    @Test
    fun noContributionsIsAnEmptyRegistry() {
        assertTrue(PatientHeaders(emptyList()).ordered.isEmpty())
        assertTrue(PatientTabs(emptyList()).ordered.isEmpty())
    }

    @Test
    fun theHistoryMergesEverySourceNewestFirst() = runBlocking {
        val older = entry("older", Instant.parse("2026-10-01T09:00:00Z"))
        val newer = entry("newer", Instant.parse("2026-11-16T09:00:00Z"))
        val middle = entry("middle", Instant.parse("2026-11-02T09:00:00Z"))
        val sources = TimelineSources(
            listOf(
                FakeSource(MutableStateFlow(listOf(older))),
                FakeSource(MutableStateFlow(listOf(newer, middle))),
            ),
        )

        assertEquals(
            listOf("newer", "middle", "older"),
            sources.entries(patient).first().map { it.id },
        )
    }

    @Test
    fun noSourcesMeansNoHistory() = runBlocking {
        assertTrue(TimelineSources(emptyList()).entries(patient).first().isEmpty())
    }

    private class FakeSource(
        private val flow: MutableStateFlow<List<TimelineEntry>>,
    ) : TimelineSource {
        override fun entries(patientId: PatientId): Flow<List<TimelineEntry>> = flow
    }

    @Test
    fun everyKindHasADistinctConstant() {
        assertEquals(
            listOf(
                TimelineKind.MEDICATION_STARTED,
                TimelineKind.MEDICATION_CHANGED,
                TimelineKind.MEDICATION_STOPPED,
                TimelineKind.STOCK,
                TimelineKind.APPOINTMENT_CONSULTATION,
                TimelineKind.APPOINTMENT_BLOOD_TEST,
                TimelineKind.APPOINTMENT_EXAM,
                TimelineKind.APPOINTMENT_CHECKUP,
                TimelineKind.APPOINTMENT_OTHER,
                TimelineKind.OTHER,
            ),
            TimelineKind.entries,
        )
    }

    @Test
    fun summarySourcesMergeInOrderAndTiesKeepRegistrationOrder() = runBlocking {
        val late = FakeSummary(order = 20, text = "appointment")
        val early = FakeSummary(order = 10, text = "medicines")
        val tied = FakeSummary(order = 10, text = "allergies")

        val summary = PatientSummarySources(listOf(late, early, tied)).summary(patient).first()

        assertEquals(listOf("medicines", "allergies", "appointment"), summary.map { it.text })
    }

    @Test
    fun noSummarySourcesMeansAnEmptySummary() = runBlocking {
        assertTrue(PatientSummarySources(emptyList()).summary(patient).first().isEmpty())
    }

    private class FakeSummary(
        override val order: Int,
        private val text: String,
    ) : PatientSummarySource {
        override fun summary(patientId: PatientId): Flow<List<PatientSummaryItem>> =
            flowOf(listOf(PatientSummaryItem(PatientSummaryIcon.MEDICATION, text)))
    }
}
