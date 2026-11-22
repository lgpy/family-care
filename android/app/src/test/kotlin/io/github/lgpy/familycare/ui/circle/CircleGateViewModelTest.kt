package io.github.lgpy.familycare.ui.circle

import app.cash.turbine.test
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleSetupState
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.sync.SyncState
import io.github.lgpy.familycare.ui.FakeAuthRepository
import io.github.lgpy.familycare.ui.FakeCareCircleRepository
import io.github.lgpy.familycare.ui.FakeSyncService
import io.github.lgpy.familycare.ui.TEST_INVITE_CODE
import io.github.lgpy.familycare.ui.awaitWhere
import io.github.lgpy.familycare.ui.testCircle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The care-circle gate: setup states, offline hint, and the error mapping of
 * "create circle" and "join with a code", against fake repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircleGateViewModelTest {

    private lateinit var circleRepository: FakeCareCircleRepository
    private lateinit var authRepository: FakeAuthRepository
    private lateinit var syncService: FakeSyncService
    private lateinit var viewModel: CircleGateViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        circleRepository = FakeCareCircleRepository()
        authRepository = FakeAuthRepository()
        syncService = FakeSyncService()
        viewModel = CircleGateViewModel(circleRepository, authRepository, syncService.state)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun notSyncedYetShowsLoading() = runTest {
        circleRepository.setSetupState(CircleSetupState.NotSyncedYet)

        viewModel.uiState.test {
            val state = awaitWhere { it is CircleGateUiState.Loading }
            assertEquals(CircleGateUiState.Loading(offline = false), state)
        }
    }

    @Test
    fun notSyncedYetWithoutConnectionAsksForTheInternet() = runTest {
        syncService.stateFlow.value = SyncState(connected = false, hasSynced = false)
        circleRepository.setSetupState(CircleSetupState.NotSyncedYet)

        viewModel.uiState.test {
            val state = awaitWhere { it is CircleGateUiState.Loading && it.offline }
            assertEquals(CircleGateUiState.Loading(offline = true), state)
        }
    }

    @Test
    fun noCircleShowsTheCreateOrJoinScreen() = runTest {
        circleRepository.setSetupState(CircleSetupState.NoCircle)

        viewModel.uiState.test {
            assertEquals(CircleGateUiState.NoCircle, awaitWhere { it == CircleGateUiState.NoCircle })
        }
    }

    @Test
    fun hasCircleShowsTheCircleAndRole() = runTest {
        circleRepository.setSetupState(
            CircleSetupState.HasCircle(testCircle, MemberRole.CAREGIVER),
        )

        viewModel.uiState.test {
            val state = awaitWhere { it is CircleGateUiState.HasCircle }
            assertEquals(CircleGateUiState.HasCircle(testCircle, MemberRole.CAREGIVER), state)
        }
    }

    @Test
    fun losingTheMembershipReturnsToNoCircle() = runTest {
        circleRepository.setSetupState(
            CircleSetupState.HasCircle(testCircle, MemberRole.CAREGIVER),
        )

        viewModel.uiState.test {
            awaitWhere { it is CircleGateUiState.HasCircle }

            // The owner removed this member; the sync stream drops the row.
            circleRepository.setSetupState(CircleSetupState.NoCircle)

            assertEquals(CircleGateUiState.NoCircle, awaitWhere { it == CircleGateUiState.NoCircle })
        }
    }

    @Test
    fun creatingWithoutANameIsRejectedBeforeTheCall() {
        viewModel.createCircle("   ")

        assertEquals(CircleGateError.InvalidName, viewModel.actionState.value.error)
        assertNull(circleRepository.lastCreatedCircleName)
        assertFalse(viewModel.actionState.value.busy)
    }

    @Test
    fun creatingACircleKeepsWorkingUntilTheRowsSync() {
        circleRepository.createCircleResult = CoreResult.Success(CareCircleId("circle-9"))

        viewModel.createCircle("  Família Silva  ")

        assertEquals("  Família Silva  ", circleRepository.lastCreatedCircleName)
        assertTrue(viewModel.actionState.value.busy)
        assertNull(viewModel.actionState.value.error)
    }

    @Test
    fun creatingOfflineShowsTheOfflineMessage() {
        circleRepository.createCircleResult = CoreResult.Failure(CoreError.Offline)

        viewModel.createCircle("Família")

        assertEquals(CircleGateError.Offline, viewModel.actionState.value.error)
        assertFalse(viewModel.actionState.value.busy)
    }

    @Test
    fun anInvalidCircleNameFromTheRepositoryHighlightsTheName() {
        circleRepository.createCircleResult =
            CoreResult.Failure(CoreError.Invalid(setOf(CoreField.CIRCLE_NAME)))

        viewModel.createCircle("Família")

        assertEquals(CircleGateError.InvalidName, viewModel.actionState.value.error)
    }

    @Test
    fun joiningNormalisesTheCodeLikeTheServer() {
        viewModel.joinWithCode("abcd 2345-efgh 6789 jkmn-2345 pqrs 6789")

        assertEquals(TEST_INVITE_CODE, circleRepository.lastAcceptedCode)
        assertTrue(viewModel.actionState.value.busy)
    }

    @Test
    fun anInvalidCodeIsRejectedBeforeTheCall() {
        viewModel.joinWithCode("not-a-code")

        assertEquals(CircleGateError.InvalidCode, viewModel.actionState.value.error)
        assertNull(circleRepository.lastAcceptedCode)
    }

    @Test
    fun aCodeForAnotherEmailSaysSo() {
        circleRepository.acceptInvitationResult = CoreResult.Failure(CoreError.EmailMismatch)

        viewModel.joinWithCode(TEST_INVITE_CODE)

        assertEquals(CircleGateError.EmailMismatch, viewModel.actionState.value.error)
    }

    @Test
    fun anExpiredOrUsedCodeIsTheSameMessage() {
        circleRepository.acceptInvitationResult = CoreResult.Failure(CoreError.InvalidCode)

        viewModel.joinWithCode(TEST_INVITE_CODE)

        assertEquals(CircleGateError.InvalidCode, viewModel.actionState.value.error)
    }

    @Test
    fun aSecondActionWhileBusyIsIgnored() {
        circleRepository.createCircleResult = CoreResult.Success(testCircle.id)
        viewModel.createCircle("Família")

        viewModel.joinWithCode(TEST_INVITE_CODE)

        assertNull(circleRepository.lastAcceptedCode)
    }

    @Test
    fun theErrorMessageCanBeDismissed() {
        circleRepository.createCircleResult = CoreResult.Failure(CoreError.Offline)
        viewModel.createCircle("Família")

        viewModel.dismissError()

        assertNull(viewModel.actionState.value.error)
    }

    @Test
    fun signingOutLetsTheUserPickTheInvitedAccount() = runTest {
        viewModel.signOut()

        assertEquals(1, authRepository.signOutCount)
    }

    // --- "Working…" must not outlive the account or the gate ------------------

    private val userA = AuthState.SignedIn(userId = "user-a", email = "a@example.com")
    private val userB = AuthState.SignedIn(userId = "user-b", email = "b@example.com")

    private fun gateFor(
        auth: MutableAuth,
        circles: CareCircleRepository = circleRepository,
    ) = CircleGateViewModel(circles, auth, syncService.state)

    @Test
    fun theNextAccountDoesNotInheritWorkingFromTheAccountThatSignedOut() = runTest {
        val auth = MutableAuth(userA)
        val gate = gateFor(auth)
        gate.createCircle("Família Silva")
        assertTrue(gate.actionState.value.busy)

        auth.state.value = AuthState.SignedOut
        auth.state.value = userB

        assertEquals(CircleGateActionState(), gate.actionState.value)
        // B can use the form.
        gate.joinWithCode(TEST_INVITE_CODE)
        assertEquals(TEST_INVITE_CODE, circleRepository.lastAcceptedCode)
    }

    @Test
    fun switchingDirectlyToAnotherAccountClearsWorkingAndTheLastError() = runTest {
        val auth = MutableAuth(userA)
        val gate = gateFor(auth)
        gate.createCircle("Família Silva")

        auth.state.value = userB

        assertFalse(gate.actionState.value.busy)

        circleRepository.createCircleResult = CoreResult.Failure(CoreError.Offline)
        gate.createCircle("Outra")
        assertEquals(CircleGateError.Offline, gate.actionState.value.error)
        auth.state.value = userA
        assertEquals(CircleGateActionState(), gate.actionState.value)
    }

    @Test
    fun workingEndsWhenTheCircleRowsArrive() = runTest {
        val auth = MutableAuth(userA)
        val gate = gateFor(auth)
        gate.createCircle("Família Silva")
        assertTrue(gate.actionState.value.busy)

        circleRepository.setSetupState(CircleSetupState.HasCircle(testCircle, MemberRole.OWNER))

        assertFalse(gate.actionState.value.busy)
    }

    @Test
    fun aCaregiverWhoJoinedAndWasRemovedSeesAUsableGate() = runTest {
        val auth = MutableAuth(userA)
        val gate = gateFor(auth)
        gate.joinWithCode(TEST_INVITE_CODE)
        assertTrue(gate.actionState.value.busy)
        circleRepository.setSetupState(CircleSetupState.HasCircle(testCircle, MemberRole.CAREGIVER))

        // The owner removes this member while the app is alive.
        circleRepository.setSetupState(CircleSetupState.NoCircle)

        assertFalse(gate.actionState.value.busy)
        circleRepository.createCircleResult = CoreResult.Success(CareCircleId("circle-2"))
        gate.createCircle("Nova família")
        assertEquals("Nova família", circleRepository.lastCreatedCircleName)
    }

    @Test
    fun aCreateThatFinishesAfterTheRowsSyncedDoesNotLeaveTheGateWorking() = runTest {
        val auth = MutableAuth(userA)
        val release = CompletableDeferred<Unit>()
        val gate = gateFor(auth, SlowCircles(circleRepository, release))
        gate.createCircle("Família Silva")

        // The sync stream outruns the RPC response.
        circleRepository.setSetupState(CircleSetupState.HasCircle(testCircle, MemberRole.OWNER))
        release.complete(Unit)

        assertFalse(gate.actionState.value.busy)
    }

    @Test
    fun aResultForTheAccountThatSignedOutIsNotShownToTheNextOne() = runTest {
        val auth = MutableAuth(userA)
        val release = CompletableDeferred<Unit>()
        val gate = gateFor(auth, SlowCircles(circleRepository, release))
        gate.createCircle("Família Silva")

        auth.state.value = AuthState.SignedOut
        auth.state.value = userB
        release.complete(Unit)

        assertEquals(CircleGateActionState(), gate.actionState.value)
    }

    @Test
    fun theNextAccountNeverSeesThePreviousAccountsCircle() = runTest {
        val auth = MutableAuth(userA)
        // A repository that has nothing to say about the new account yet.
        val setup = MutableSharedFlow<CircleSetupState>()
        val gate = gateFor(auth, SetupFrom(circleRepository, setup))

        gate.uiState.test {
            assertEquals(CircleGateUiState.Loading(), awaitItem())
            setup.emit(CircleSetupState.HasCircle(testCircle, MemberRole.OWNER))
            awaitWhere { it is CircleGateUiState.HasCircle }

            auth.state.value = userB

            // B's gate starts from "loading" instead of replaying A's circle.
            assertEquals(CircleGateUiState.Loading(), awaitItem())
            assertEquals(CircleGateUiState.Loading(), gate.uiState.value)
        }
    }

    @Test
    fun errorsMapToTheMessagesTheScreenCanShow() {
        assertEquals(CircleGateError.InvalidCode, CoreError.InvalidCode.toGateError())
        assertEquals(CircleGateError.EmailMismatch, CoreError.EmailMismatch.toGateError())
        assertEquals(
            CircleGateError.EmailNotConfirmed,
            CoreError.EmailNotConfirmed.toGateError(),
        )
        assertEquals(CircleGateError.Offline, CoreError.Offline.toGateError())
        assertEquals(CircleGateError.NotAllowed, CoreError.NotAllowed.toGateError())
        assertEquals(
            CircleGateError.InvalidName,
            CoreError.Invalid(setOf(CoreField.CIRCLE_NAME)).toGateError(),
        )
        // A field this screen does not own is not shown as a name problem.
        assertEquals(
            CircleGateError.Unknown,
            CoreError.Invalid(setOf(CoreField.INVITATION_EMAIL)).toGateError(),
        )
        assertEquals(CircleGateError.Unknown, CoreError.Unknown.toGateError())
        assertEquals(CircleGateError.Unknown, CoreError.LastOwner.toGateError())
    }
}

/** An [AuthRepository] whose signed-in user a test can change. */
private class MutableAuth(initial: AuthState) : AuthRepository {
    override val state = MutableStateFlow(initial)

    override suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun signOut(): Result<Unit> {
        state.value = AuthState.SignedOut
        return Result.success(Unit)
    }
}

/** Holds "create circle" until [release] completes, like a slow network call. */
private class SlowCircles(
    private val base: FakeCareCircleRepository,
    private val release: CompletableDeferred<Unit>,
) : CareCircleRepository by base {
    override suspend fun createCircle(name: String): CoreResult<CareCircleId> {
        release.await()
        return base.createCircle(name)
    }
}

/** The fake repository with a [setupState] the test feeds by hand. */
private class SetupFrom(
    base: FakeCareCircleRepository,
    override val setupState: Flow<CircleSetupState>,
) : CareCircleRepository by base
