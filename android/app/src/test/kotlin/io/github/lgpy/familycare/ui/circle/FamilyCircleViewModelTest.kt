package io.github.lgpy.familycare.ui.circle

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.InvitationId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.ui.FakeCareCircleRepository
import io.github.lgpy.familycare.ui.TEST_INVITE_CODE
import io.github.lgpy.familycare.ui.awaitWhere
import io.github.lgpy.familycare.ui.caregiverMember
import io.github.lgpy.familycare.ui.ownerMember
import io.github.lgpy.familycare.ui.testCircle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * More → Family circle: the member list, the invite flow (code shown once), the
 * pending invitations and the confirmations before removing or revoking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FamilyCircleViewModelTest {

    private lateinit var circles: FakeCareCircleRepository
    private lateinit var viewModel: FamilyCircleViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        circles = FakeCareCircleRepository()
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.OWNER,
            members = listOf(ownerMember, caregiverMember),
        )
        viewModel = FamilyCircleViewModel(circles)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun invitation(
        id: String = "invitation-1",
        email: String = "ana@example.com",
    ): Invitation = Invitation(
        id = InvitationId(id),
        circleId = testCircle.id,
        email = email,
        role = MemberRole.CAREGIVER,
    )

    @Test
    fun theCircleAndItsMembersComeFromTheLocalData() = runTest {
        viewModel.uiState.test {
            val state = awaitWhere { it.loaded }

            assertEquals("Família", state.circleName)
            assertEquals(listOf(ownerMember, caregiverMember), state.members)
            assertTrue(state.isOwner)
        }
    }

    @Test
    fun aCaregiverSeesNoOwnerActions() = runTest {
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.CAREGIVER,
            members = listOf(ownerMember, caregiverMember.copy(isMe = true)),
        )

        viewModel.uiState.test {
            assertFalse(awaitWhere { it.loaded }.isOwner)
        }
    }

    @Test
    fun invitingReturnsTheCodeFormattedAndOnlyOnce() = runTest {
        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.invite("  Ana@Example.COM ")

            val showing = awaitWhere { it.shownInvite != null }
            val invite = requireNotNull(showing.shownInvite)
            assertEquals("ana@example.com", invite.email)
            assertEquals("ABCD2345-EFGH6789-JKMN2345-PQRS6789", invite.code)
            assertEquals(
                "ana@example.com" to MemberRole.CAREGIVER,
                circles.lastCreatedInvitation,
            )
            // The newly created invitation is fetched for the pending list.
            assertEquals(1, circles.listInvitationsCalls)

            viewModel.dismissInvite()

            val dismissed = awaitWhere { it.shownInvite == null }
            assertNull(dismissed.shownInvite)
            assertEquals(1, circles.createInvitationCalls)
        }
    }

    @Test
    fun anInvalidEmailIsRejectedBeforeTheCall() = runTest {
        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.invite("not-an-email")

            assertEquals(
                FamilyCircleError.InvalidEmail,
                awaitWhere { it.inviteError != null }.inviteError,
            )
            assertEquals(0, circles.createInvitationCalls)
        }
    }

    @Test
    fun invitingOfflineShowsTheOfflineMessage() = runTest {
        circles.createInvitationResult = CoreResult.Failure(CoreError.Offline)

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.invite("ana@example.com")

            assertEquals(
                FamilyCircleError.Offline,
                awaitWhere { it.inviteError != null }.inviteError,
            )
            assertNull(viewModel.uiState.value.shownInvite)
        }
    }

    @Test
    fun aCaregiverCannotInvite() = runTest {
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.CAREGIVER,
            members = listOf(ownerMember, caregiverMember.copy(isMe = true)),
        )

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.invite("ana@example.com")

            assertFalse(viewModel.uiState.value.inviteBusy)
            assertEquals(0, circles.createInvitationCalls)
        }
    }

    @Test
    fun thePendingInvitationsAreLoadedOnDemand() = runTest {
        circles.pendingInvitationsResult = CoreResult.Success(listOf(invitation()))

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.refreshInvitations()

            val loaded = awaitWhere { it.invitationsLoaded }
            assertEquals(listOf(invitation()), loaded.invitations)
            assertEquals(1, circles.listInvitationsCalls)
        }
    }

    @Test
    fun listingInvitationsOfflineShowsTheOfflineMessage() = runTest {
        circles.pendingInvitationsResult = CoreResult.Failure(CoreError.Offline)

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.refreshInvitations()

            assertEquals(
                FamilyCircleError.Offline,
                awaitWhere { it.invitationsError != null }.invitationsError,
            )
        }
    }

    @Test
    fun removingAMemberAsksForConfirmationFirst() = runTest {
        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.requestRemoveMember(caregiverMember)

            assertEquals(caregiverMember, awaitWhere { it.pendingRemoval != null }.pendingRemoval)
            assertTrue(circles.removedMemberships.isEmpty())

            viewModel.confirmRemoveMember()

            assertNull(awaitWhere { it.pendingRemoval == null }.pendingRemoval)
            assertEquals(
                listOf(caregiverMember.membershipId),
                circles.removedMemberships,
            )
        }
    }

    @Test
    fun cancellingTheConfirmationRemovesNobody() = runTest {
        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.requestRemoveMember(caregiverMember)
            awaitWhere { it.pendingRemoval != null }

            viewModel.cancelRemoveMember()

            assertNull(awaitWhere { it.pendingRemoval == null }.pendingRemoval)
            assertTrue(circles.removedMemberships.isEmpty())
        }
    }

    @Test
    fun theSignedInMemberCannotRemoveThemselves() = runTest {
        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.requestRemoveMember(ownerMember)

            assertNull(viewModel.uiState.value.pendingRemoval)
        }
    }

    @Test
    fun removingTheLastOwnerShowsTheLastOwnerMessage() = runTest {
        circles.removeMemberResult = CoreResult.Failure(CoreError.LastOwner)

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.requestRemoveMember(caregiverMember)
            awaitWhere { it.pendingRemoval != null }
            viewModel.confirmRemoveMember()

            assertEquals(
                FamilyCircleError.LastOwner,
                awaitWhere { it.actionError != null }.actionError,
            )
        }
    }

    @Test
    fun revokingAnInvitationAsksForConfirmationAndDropsItFromTheList() = runTest {
        val pending = invitation()
        circles.pendingInvitationsResult = CoreResult.Success(listOf(pending))

        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.refreshInvitations()
            awaitWhere { it.invitations.isNotEmpty() }

            viewModel.requestRevoke(pending)

            assertEquals(pending, awaitWhere { it.pendingRevoke != null }.pendingRevoke)

            viewModel.confirmRevoke()

            val revoked = awaitWhere { it.invitations.isEmpty() }
            assertNull(revoked.pendingRevoke)
            assertEquals(listOf(pending.id), circles.revokedInvitations)
        }
    }

    @Test
    fun anAlreadyAcceptedInvitationSaysItWasUsed() = runTest {
        circles.revokeInvitationResult = CoreResult.Failure(CoreError.AlreadyAccepted)
        val pending = invitation()

        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.requestRevoke(pending)
            awaitWhere { it.pendingRevoke != null }

            viewModel.confirmRevoke()

            assertEquals(
                FamilyCircleError.AlreadyAccepted,
                awaitWhere { it.actionError != null }.actionError,
            )
        }
    }

    @Test
    fun errorsMapToTheMessagesTheScreenCanShow() {
        assertEquals(
            FamilyCircleError.InvalidEmail,
            CoreError.Invalid(setOf(CoreField.INVITATION_EMAIL)).toFamilyCircleError(),
        )
        assertEquals(FamilyCircleError.Offline, CoreError.Offline.toFamilyCircleError())
        assertEquals(FamilyCircleError.NotAllowed, CoreError.NotAllowed.toFamilyCircleError())
        assertEquals(FamilyCircleError.LastOwner, CoreError.LastOwner.toFamilyCircleError())
        assertEquals(
            FamilyCircleError.AlreadyAccepted,
            CoreError.AlreadyAccepted.toFamilyCircleError(),
        )
        assertEquals(FamilyCircleError.NotFound, CoreError.NotFound.toFamilyCircleError())
        assertEquals(FamilyCircleError.Unknown, CoreError.Unknown.toFamilyCircleError())
    }

    @Test
    fun aRevokeFailureLeavesTheInvitationInTheList() = runTest {
        circles.revokeInvitationResult = CoreResult.Failure(CoreError.Offline)
        val pending = invitation()
        circles.pendingInvitationsResult = CoreResult.Success(listOf(pending))

        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.refreshInvitations()
            awaitWhere { it.invitations.isNotEmpty() }

            viewModel.requestRevoke(pending)
            awaitWhere { it.pendingRevoke != null }
            viewModel.confirmRevoke()

            val failed = awaitWhere { it.actionError != null }
            assertEquals(listOf(pending), failed.invitations)
        }
    }

    @Test
    fun aFailedRemoveKeepsTheMemberInTheList() = runTest {        circles.removeMemberResult = CoreResult.Failure(CoreError.Offline)

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.requestRemoveMember(caregiverMember)
            awaitWhere { it.pendingRemoval != null }
            viewModel.confirmRemoveMember()

            val failed = awaitWhere { it.actionError != null }
            assertEquals(FamilyCircleError.Offline, failed.actionError)
            assertTrue(failed.members.contains(caregiverMember))
        }
    }
}
