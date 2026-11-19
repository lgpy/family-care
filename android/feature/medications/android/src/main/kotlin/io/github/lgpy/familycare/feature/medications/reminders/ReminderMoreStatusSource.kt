package io.github.lgpy.familycare.feature.medications.reminders

import io.github.lgpy.familycare.core.ui.status.MoreStatus
import io.github.lgpy.familycare.core.ui.status.MoreStatusSource

/**
 * The reminders row's status on the More screen (D5/D19).
 *
 * The More screen belongs to the shell and cannot import this module's health
 * checker, so this adapter implements the core [MoreStatusSource] contract and
 * the app registers it. The report is only a count: the setup screen still owns
 * the details of what needs fixing.
 */
class ReminderMoreStatusSource(
    private val checker: ReminderHealthChecker,
) : MoreStatusSource {

    override suspend fun reminders(): MoreStatus? =
        runCatching { checker.check() }
            .getOrNull()
            ?.let { health -> MoreStatus(problems = health.problems.size) }
}
