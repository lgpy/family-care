package io.github.lgpy.familycare.core.ui.dashboard

import androidx.compose.runtime.Composable

/**
 * What the shell lets a card do besides drawing itself. Every action is a
 * callback the shell owns, so a card can start a flow without naming another
 * module's screen (D5).
 *
 * @param onNavigate Opens a route the card's module owns (a dose slot, an
 *   appointment's detail, a setup screen).
 * @param onAddPatient Opens the shell's Add patient form. A card uses it only
 *   when its own empty state needs the one action that starts a new circle.
 */
class DashboardActions(
    val onNavigate: (String) -> Unit,
    val onAddPatient: () -> Unit,
)

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
     * The card's content. The card owns its view model and asks the shell for
     * anything outside itself through [actions], so it can push its own detail
     * screen or open the shared Add patient form without the shell importing
     * the module's screens.
     */
    @Composable
    fun Content(actions: DashboardActions)
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
