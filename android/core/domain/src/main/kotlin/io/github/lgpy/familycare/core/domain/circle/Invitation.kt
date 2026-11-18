package io.github.lgpy.familycare.core.domain.circle

import kotlin.time.Clock
import kotlin.time.Instant

/** Identity of one `core.invitations` row. */
@JvmInline
value class InvitationId(val value: String) {
    init {
        require(value.isNotBlank()) { "An invitation id must not be blank" }
    }
}

/**
 * An invitation to join a circle with a given role, bound to an email address.
 *
 * It carries **no** code: only the sha256 of the normalised code is stored
 * server-side (`token_hash`), the plaintext code is returned once by
 * [CareCircleRepository.createInvitation] and never kept. Invitations are not
 * synced; [CareCircleRepository.listPendingInvitations] reads them online.
 *
 * @param email Lower-case, as the server stores it. Accepting requires the
 *   signed-in user's confirmed email to match it.
 * @param expiresAt Seven days after creation, server-clock.
 * @param acceptedAt Set when it was used; a used invitation is no longer pending.
 * @param revokedAt Set when an owner cancelled it.
 */
data class Invitation(
    val id: InvitationId,
    val circleId: CareCircleId,
    val email: String,
    val role: MemberRole,
    val expiresAt: Instant? = null,
    val createdAt: Instant? = null,
    val acceptedAt: Instant? = null,
    val revokedAt: Instant? = null,
) {
    /** Whether the invitation can still be accepted at [now]. */
    fun isPending(now: Instant = Clock.System.now()): Boolean =
        acceptedAt == null &&
            revokedAt == null &&
            (expiresAt == null || expiresAt > now)
}

/**
 * The invitation-code and email rules, mirroring the M2 migration.
 *
 * `core.create_invitation()` produces 160 random bits as Crockford base32
 * (alphabet without I, L, O and U), displayed `XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX`.
 * `core.accept_invitation()` normalises a typed code by upper-casing it and
 * removing whitespace and dashes before hashing, so "abcd 1234-…" and
 * "ABCD1234…" are the same invitation. Both directions are reproduced here so a
 * screen can validate a code before spending a network round trip, and the
 * normalisation can never disagree with the server's.
 */
object InvitationRules {

    /** The 32 characters a code contains after normalisation. */
    const val CODE_LENGTH: Int = 32

    /** Crockford base32: digits plus A–Z without I, L, O and U. */
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Mirrors `invitations_email_length`. */
    const val MIN_EMAIL_LENGTH: Int = 3
    const val MAX_EMAIL_LENGTH: Int = 320

    /** How many characters sit in one displayed group. */
    private const val CODE_GROUP_LENGTH: Int = 8

    /** Mirrors the server's `^[^@[:space:]]+@[^@[:space:]]+$` and the length check. */
    private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+$")

    /**
     * The form the server hashes: upper-case, with whitespace and dashes
     * removed. Applied before sending a code to `core.accept_invitation()`.
     */
    fun normalizeCode(code: String): String =
        code.filterNot { it.isWhitespace() || it == '-' }.uppercase()

    /**
     * Whether [code] can be an invitation code: 32 characters of [ALPHABET]
     * after normalisation. Characters outside the alphabet (such as the
     * excluded I, L, O, U) are not "fixed" to a lookalike: they simply make the
     * code invalid, the same way it would not match the stored hash.
     */
    fun isValidCode(code: String): Boolean {
        val normalized = normalizeCode(code)
        return normalized.length == CODE_LENGTH && normalized.all { it in ALPHABET }
    }

    /**
     * The code as the server displays it (`XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX`),
     * for showing and sharing. A string that is not a valid code is returned
     * normalised but ungrouped.
     */
    fun formatCode(code: String): String {
        val normalized = normalizeCode(code)
        if (normalized.length != CODE_LENGTH) return normalized
        return normalized.chunked(CODE_GROUP_LENGTH).joinToString("-")
    }

    /** The form the server stores: trimmed and lower-case. */
    fun normalizeEmail(email: String): String = email.trim().lowercase()

    /**
     * Whether [email] is acceptable for an invitation, using the same rule as
     * `core.create_invitation()`: one `@`, no whitespace around it, 3..320
     * characters. Deliberately not stricter than the server: it only has to
     * match the invited Google account.
     */
    fun isValidEmail(email: String): Boolean {
        val normalized = normalizeEmail(email)
        return normalized.length in MIN_EMAIL_LENGTH..MAX_EMAIL_LENGTH &&
            EMAIL_PATTERN.matches(normalized)
    }
}
