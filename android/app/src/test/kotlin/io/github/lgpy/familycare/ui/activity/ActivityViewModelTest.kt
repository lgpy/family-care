package io.github.lgpy.familycare.ui.activity

import app.cash.turbine.test
import io.github.lgpy.familycare.core.data.activity.ActivityEvent
import io.github.lgpy.familycare.core.data.activity.ActivityRepository
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.ui.FakeCareCircleRepository
import io.github.lgpy.familycare.ui.awaitWhere
import io.github.lgpy.familycare.ui.caregiverMember
import io.github.lgpy.familycare.ui.ownerMember
import io.github.lgpy.familycare.ui.testCircle
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The owner-only activity feed: first page, paging and the error states. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private lateinit var circles: FakeCareCircleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        circles = FakeCareCircleRepository().apply {
            setCircle(
                circle = testCircle,
                role = MemberRole.OWNER,
                members = listOf(ownerMember, caregiverMember),
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theFirstPageLoadsAndAShortPageEndsTheFeed() = runTest {
        val repository = FakeActivityRepository(CoreResult.Success(listOf(event("a"), event("b"))))

        viewModel(repository).uiState.test {
            val state = awaitWhere { !it.loading }

            assertEquals(listOf("a", "b"), state.events.map { it.id })
            assertTrue("Two rows are fewer than a page", state.endReached)
            assertEquals(testCircle.id.value, repository.circles.single())
        }
    }

    @Test
    fun loadMoreAppendsUsingTheLastRowAsTheCursor() = runTest {
        val first = events("e", 50)
        val second = listOf(event("older-1"), event("older-2"))
        val repository = FakeActivityRepository(
            CoreResult.Success(first),
            CoreResult.Success(second),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            val loaded = awaitWhere { !it.loading && it.events.size == 50 }
            assertFalse(loaded.endReached)

            viewModel.loadMore()

            val appended = awaitWhere { it.events.size == 52 }
            assertEquals(first.map { it.id } + second.map { it.id }, appended.events.map { it.id })
            assertTrue("The second page was short", appended.endReached)
            assertEquals(first.last().occurredAt, repository.befores[1])
        }
    }

    @Test
    fun aFullSecondPageStillOffersMore() = runTest {
        val repository = FakeActivityRepository(
            CoreResult.Success(events("first", 50)),
            CoreResult.Success(events("second", 50)),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            awaitWhere { it.events.size == 50 }
            viewModel.loadMore()
            val state = awaitWhere { it.events.size == 100 }

            assertFalse(state.endReached)
            assertEquals(100, state.events.size)
        }
    }

    @Test
    fun aFailedRefreshReportsTheErrorAndKeepsNoRows() = runTest {
        val repository = FakeActivityRepository(CoreResult.Failure(CoreError.Offline))

        viewModel(repository).uiState.test {
            val state = awaitWhere { !it.loading }

            assertEquals(CoreError.Offline, state.error)
            assertTrue(state.events.isEmpty())
        }
    }

    @Test
    fun aFailureAfterAPageKeepsTheRowsItAlreadyHad() = runTest {
        val repository = FakeActivityRepository(
            CoreResult.Success(events("e", 50)),
            CoreResult.Failure(CoreError.Offline),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            awaitWhere { it.events.size == 50 }
            viewModel.loadMore()

            val state = awaitWhere { it.error != null }
            assertEquals(CoreError.Offline, state.error)
            assertEquals(50, state.events.size)
        }
    }

    @Test
    fun aCaregiverIsRefusedWithoutCallingTheRpc() = runTest {
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.CAREGIVER,
            members = listOf(ownerMember.copy(role = MemberRole.CAREGIVER), caregiverMember),
        )
        val repository = FakeActivityRepository()

        viewModel(repository).uiState.test {
            val state = awaitWhere { !it.loading }

            assertEquals(CoreError.NotAllowed, state.error)
            assertTrue(repository.circles.isEmpty())
        }
    }

    private fun viewModel(repository: ActivityRepository) = ActivityViewModel(
        activityRepository = repository,
        careCircleRepository = circles,
    )

    private fun events(prefix: String, count: Int): List<ActivityEvent> =
        (1..count).map { index -> event("$prefix-$index", occurredAt = Instant.fromEpochSeconds(1_000_000L - index)) }

    private fun event(
        id: String,
        occurredAt: Instant = Instant.fromEpochSeconds(1_000_000L),
    ) = ActivityEvent(
        id = id,
        occurredAt = occurredAt,
        eventKind = "data",
        action = "insert",
        tableName = "medications",
        actorName = "Leonardo",
        patientName = "Avó",
        label = "Metformin",
    )

    private class FakeActivityRepository(
        vararg results: CoreResult<List<ActivityEvent>>,
    ) : ActivityRepository {

        private val pages = ArrayDeque(results.toList())
        val circles = mutableListOf<String>()
        val befores = mutableListOf<Instant?>()

        override suspend fun activity(
            circleId: String,
            before: Instant?,
            limit: Int,
        ): CoreResult<List<ActivityEvent>> {
            circles += circleId
            befores += before
            return pages.removeFirstOrNull() ?: CoreResult.Success(emptyList())
        }
    }
}
