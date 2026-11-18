package io.github.lgpy.familycare.core.ui.dashboard

import androidx.compose.runtime.Composable

/**
 * One card of the Today dashboard, contributed by a feature module
 * (docs/architecture.md §5 "Shared screens are filled through extension
 * points").
 *
 * A module registers an implementation in the app's dependency graph
 * (`single<DashboardCard> { … }`); the shell renders every registered card
 * sorted by [order]. Cards know nothing about each other and the shell names no
 * feature module (D5): Today simply draws what it was given.
 *
 * [order] is the card's urgency/position, lower first. The shell leaves gaps
 * between the values it knows about (needs-attention items at 0, doses at 100,
 * the next seven days at 200) so a module can slot in without renumbering.
 */
interface DashboardCard {

    /** Lower sorts first on Today. */
    val order: Int

    /**
     * The card's content. The card owns its view model; [onNavigate] asks the
     * shell to open a route the module owns, so a card can push its own detail
     * screen without the shell importing the module's screens.
     */
    @Composable
    fun Content(onNavigate: (String) -> Unit)
}

/**
 * Every card the shell should render, collected once from Koin's `getAll()`.
 *
 * A wrapper type (rather than injecting `List<DashboardCard>`) keeps the
 * multibinding in the dependency graph, where the modules are wired, and keeps
 * the shell free of Koin generics.
 */
class DashboardCards(cards: List<DashboardCard>) {

    /** The cards, most urgent first; equal orders keep registration order. */
    val ordered: List<DashboardCard> = cards.sortedBy { it.order }
}
