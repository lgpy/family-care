package io.github.lgpy.familycare.core.data.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The nonce for the Google → Supabase sign-in handshake (D12).
 *
 * Google puts `sha256(rawNonce)` into the ID token's `nonce` claim; Supabase
 * hashes the nonce it receives and compares. Only the hash goes to Google and
 * only the raw value goes to Supabase, so the two halves agree.
 *
 * Not Android-specific and injectable, so the hash can be unit tested against
 * a known digest without network or RNG.
 */
object GoogleSignInNonce {

    /** 32 bytes of entropy → a 43-character base64url nonce. */
    const val BYTE_COUNT: Int = 32

    private const val HASH_ALGORITHM = "SHA-256"
    private const val HEX_DIGITS = "0123456789abcdef"

    private val secureRandom = SecureRandom()
    private val urlSafeEncoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * A fresh random nonce, base64url without padding.
     *
     * @param bytes injectable entropy source, used by tests to pin a value.
     */
    fun generate(bytes: (Int) -> ByteArray = { size -> ByteArray(size).also(secureRandom::nextBytes) }): String =
        urlSafeEncoder.encodeToString(bytes(BYTE_COUNT))

    /**
     * Lowercase hex SHA-256 of [rawNonce] — exactly what the Google Credential
     * Manager request carries.
     */
    fun hash(rawNonce: String): String {
        require(rawNonce.isNotEmpty()) { "A nonce must not be empty" }
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            .digest(rawNonce.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }
}
