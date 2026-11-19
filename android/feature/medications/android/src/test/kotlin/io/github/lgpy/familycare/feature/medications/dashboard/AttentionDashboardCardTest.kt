package io.github.lgpy.familycare.feature.medications.dashboard

import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.slot
import io.github.lgpy.familycare.feature.medications.todayGroup
import io.github.lgpy.familycare.feature.medications.reminders.ReminderHealth
import io.github.lgpy.familycare.feature.medications.reminders.ReminderProblem
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merged Today "Needs attention" card: one list, in the mockup's order
 * (doses, low stock, reminder health), and nothing at all when there is nothing
 * to say.
 */
class AttentionDashboardCardTest {

    private val scheduled: LocalDateTime = LocalDateTime.of(2026, 11, 16, 8, 0)
    private val regimen = regimen(id = "reg-1", medicationId = "med-1")

    private val doseGroup = DoseGroupUi(
        group = todayGroup(listOf(slot(regimen, scheduled))),
        patientName = "Grandpa",
        state = DoseGroupState.OVERDUE,
        givenBy = null,
        at = null,
        pills = emptyList(),
    )

    private val lowStockLine = LowStockLine(
        medicationId = "med-1",
        patientId = "patient-1",
        patientName = "Grandpa",
        medicationName = "Metformin",
        strength = "850 mg",
        daysRemaining = 5,
        needsPrescription = true,
    )

    private val unhealthy = ReminderHealth(
        notificationsAllowed = true,
        channelEnabled = true,
        exactAlarmsAllowed = true,
        batteryOptimisationIgnored = false,
        scheduledCount = 2,
        plannedCount = 2,
        nextAlarmAt = null,
    )

    @Test
    fun `doses come first, then low stock, then reminder health`() {
        val rows = attentionRows(
            doses = NeedsAttentionUiState(groups = listOf(doseGroup)),
            stock = LowStockUiState(loaded = true, lines = listOf(lowStockLine)),
            health = unhealthy,
        )

        assertEquals(
            listOf("DoseGroup", "LowStock", "Reminders"),
            rows.map { it::class.simpleName },
        )
    }

    @Test
    fun `low stock beyond the five lines becomes one more row`() {
        val rows = attentionRows(
            doses = NeedsAttentionUiState(),
            stock = LowStockUiState(loaded = true, lines = listOf(lowStockLine), more = 3),
            health = null,
        )

        assertEquals(2, rows.size)
        assertEquals(AttentionRow.LowStockMore(3), rows.last())
    }

    @Test
    fun `a healthy reminder setup contributes no row`() {
        val healthy = ReminderHealth(
            notificationsAllowed = true,
            channelEnabled = true,
            exactAlarmsAllowed = true,
            batteryOptimisationIgnored = true,
            scheduledCount = 0,
            plannedCount = 0,
            nextAlarmAt = null,
        )

        val rows = attentionRows(
            doses = NeedsAttentionUiState(),
            stock = LowStockUiState(loaded = true),
            health = healthy,
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `the reminder row names the problems the check found`() {
        val rows = attentionRows(
            doses = NeedsAttentionUiState(),
            stock = LowStockUiState(loaded = true),
            health = unhealthy,
        )

        assertEquals(
            AttentionRow.Reminders(listOf(ReminderProblem.BATTERY_OPTIMISATION)),
            rows.single(),
        )
    }

    @Test
    fun `nothing at all means the card is hidden`() {
        val rows = attentionRows(
            doses = NeedsAttentionUiState(loaded = true),
            stock = LowStockUiState(loaded = true),
            health = null,
        )

        assertTrue(rows.isEmpty())
    }
}
