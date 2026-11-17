package io.github.lgpy.familycare.core.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInNonceTest {

    @Test
    fun hashMatchesTheKnownSha256OfTheInput() {
        // RFC 6234 test vector for "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GoogleSignInNonce.hash("abc"),
        )
    }

    @Test
    fun hashIsLowercaseHexOfTheDigestLength() {
        val hash = GoogleSignInNonce.hash("a nonce with spaces and ünïcode")

        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun hashIsStableForTheSameNonce() {
        assertEquals(GoogleSignInNonce.hash("same"), GoogleSignInNonce.hash("same"))
    }

    @Test
    fun hashRejectsAnEmptyNonce() {
        assertThrows(IllegalArgumentException::class.java) { GoogleSignInNonce.hash("") }
    }

    @Test
    fun generateEncodesTheRequestedEntropyAsUnpaddedBase64Url() {
        var requested: Int? = null

        val nonce = GoogleSignInNonce.generate { size ->
            requested = size
            ByteArray(size) { 0 }
        }

        assertEquals(GoogleSignInNonce.BYTE_COUNT, requested)
        // 32 zero bytes in base64url without padding: 43 'A's.
        assertEquals(43, nonce.length)
        assertEquals("A".repeat(43), nonce)
        assertTrue(!nonce.contains('='))
    }

    @Test
    fun generatedNoncesDiffer() {
        assertNotEquals(GoogleSignInNonce.generate(), GoogleSignInNonce.generate())
    }
}
