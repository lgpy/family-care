package io.github.lgpy.familycare.core.domain.circle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Invitation code and email rules, mirroring `core.create_invitation()` and
 * `core.accept_invitation()` in the M2 migration.
 */
class InvitationRulesTest {

    /** What `core.create_invitation()` returns for one invitation. */
    private val code = "K7QW3ZP9-4M2XAB6T-8H5R1NJD-0CFGVYSE"

    @Test
    fun `a display code is valid as it is`() {
        assertTrue(InvitationRules.isValidCode(code))
    }

    @Test
    fun `normalisation removes whitespace and dashes and upper-cases`() {
        assertEquals(
            "K7QW3ZP94M2XAB6T8H5R1NJD0CFGVYSE",
            InvitationRules.normalizeCode(" k7qw3zp9 - 4m2xab6t-8h5r1njd-0cfgvyse "),
        )
    }

    @Test
    fun `the server form is grouped eight by eight`() {
        assertEquals(code, InvitationRules.formatCode(InvitationRules.normalizeCode(code)))
        assertEquals(code, InvitationRules.formatCode("k7qw3zp94m2xab6t8h5r1njd0cfgvyse"))
    }

    @Test
    fun `a code of the wrong length is invalid`() {
        assertFalse(InvitationRules.isValidCode(""))
        assertFalse(InvitationRules.isValidCode("K7QW3ZP9"))
        assertFalse(InvitationRules.isValidCode("K7QW3ZP94M2XAB6T8H5R1NJD0CFGVYSEX"))
    }

    @Test
    fun `the crockford-excluded letters make a code invalid`() {
        // I, L, O and U are not in the alphabet; they are not "fixed" to 1/0.
        val alphabetCode = "K7QW3ZP94M2XAB6T8H5R1NJD0CFGVYSE"
        listOf('I', 'L', 'O', 'U').forEach { excluded ->
            val withExcluded = alphabetCode.toCharArray().also {
                it[0] = excluded
            }.concatToString()

            assertFalse(InvitationRules.isValidCode(withExcluded), "letter $excluded")
        }
    }

    @Test
    fun `formatting a short string leaves it normalised but ungrouped`() {
        assertEquals("ABC", InvitationRules.formatCode("a b-c"))
    }

    @Test
    fun `emails are trimmed and lower-cased`() {
        assertEquals("mom@example.test", InvitationRules.normalizeEmail("  Mom@Example.TEST "))
    }

    @Test
    fun `ordinary addresses are accepted`() {
        assertTrue(InvitationRules.isValidEmail("mom@example.test"))
        assertTrue(InvitationRules.isValidEmail("a@b"))
        assertTrue(InvitationRules.isValidEmail("first.last+tag@mail.example.pt"))
    }

    @Test
    fun `addresses the server would reject are rejected`() {
        assertFalse(InvitationRules.isValidEmail(""))
        assertFalse(InvitationRules.isValidEmail("no-at-sign"))
        assertFalse(InvitationRules.isValidEmail("two@at@example.test"))
        assertFalse(InvitationRules.isValidEmail("space in@example.test"))
        assertFalse(InvitationRules.isValidEmail("@example.test"))
        assertFalse(InvitationRules.isValidEmail("mom@"))
        assertFalse(
            InvitationRules.isValidEmail("a".repeat(320) + "@example.test"),
            "longer than 320 characters",
        )
    }

    @Test
    fun `an invitation is pending until it is used revoked or expires`() {
        val now = Instant.parse("2026-11-18T12:00:00Z")
        val pending = invitation(expiresAt = Instant.parse("2026-11-25T12:00:00Z"))

        assertTrue(pending.isPending(now))
        assertFalse(pending.copy(acceptedAt = now).isPending(now))
        assertFalse(pending.copy(revokedAt = now).isPending(now))
        assertFalse(
            pending.copy(expiresAt = Instant.parse("2026-11-18T11:59:59Z")).isPending(now),
        )
    }

    private fun invitation(expiresAt: Instant?) = Invitation(
        id = InvitationId("invitation-1"),
        circleId = CareCircleId("circle-1"),
        email = "mom@example.test",
        role = MemberRole.CAREGIVER,
        expiresAt = expiresAt,
    )
}
