package io.github.lgpy.familycare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import io.github.lgpy.familycare.core.ui.dashboard.DashboardCards
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.koin.compose.koinInject

/**
 * Today: the start destination, sorted by urgency rather than by module
 * (docs/ux/screens.md §1, docs/ux/mockups/scaling.html).
 *
 * The header is the title and the full date: the greeting, role and circle
 * lines were removed in phase 2 because the date is the one thing the user
 * needs at a glance. Every card below comes from a feature module through the
 * dashboard extension point ([DashboardCards]); Today imports no module: it
 * draws what was registered and passes [onOpenRoute] so a card can open its own
 * screens.
 */
@Composable
fun TodayScreen(
    modifier: Modifier = Modifier,
    onOpenRoute: (String) -> Unit = {},
) {
    val dashboard = koinInject<DashboardCards>()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TodayHeader()

        dashboard.ordered.forEach { card ->
            card.Content(onNavigate = onOpenRoute)
        }
    }
}

/** "Today" with the full, locale-aware date underneath ("quinta-feira, 19 de novembro"). */
@Composable
private fun TodayHeader() {
    val locale = LocalConfiguration.current.locales[0]
    // The skeleton asks the platform for this locale's own order and its
    // connector words, so en gives "Thursday, 19 November" and pt-PT
    // "quinta-feira, 19 de novembro" without a hard-coded pattern per language.
    val pattern = remember(locale) {
        android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
    }
    val today = LocalDate.now()
    val date = remember(today, locale, pattern) {
        today.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    ScreenHeader(
        title = stringResource(R.string.today_title),
        subtitle = date,
    )
}
