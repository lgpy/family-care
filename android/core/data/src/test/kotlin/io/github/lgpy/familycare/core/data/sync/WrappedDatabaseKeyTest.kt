package io.github.lgpy.familycare.core.data.sync

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The local database key: generated once, wrapped before it is stored, and
 * unwrapped to the same passphrase on every start. A fake cipher stands in for
 * the Android Keystore, so this runs on the JVM without an Android runtime.
 */
class WrappedDatabaseKeyTest {

    /** Reversible stand-in for AES-GCM; the shift proves the key was obscured. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): SealedBytes =
            SealedBytes(iv = byteArrayOf(7, 7, 7), ciphertext = plaintext.map { (it + 42).toByte() }.toByteArray())

        override fun decrypt(sealed: SealedBytes): ByteArray =
            sealed.ciphertext.map { (it - 42).toByte() }.toByteArray()
    }

    private class MemoryBlobStore : KeyBlobStore {
        var value: String? = null
        var writes: Int = 0

        override fun read(): String? = value

        override fun write(value: String) {
            this.value = value
            writes++
        }
    }

    @Test
    fun createsA32ByteKeyOnFirstUse() {
        val blobs = MemoryBlobStore()

        val passphrase = WrappedDatabaseKey(blobs, FakeCipher()).passphrase()

        assertEquals(WrappedDatabaseKey.KEY_BYTES, Base64.getDecoder().decode(passphrase).size)
        assertEquals(1, blobs.writes)
    }

    @Test
    fun reusesTheStoredKey() {
        val blobs = MemoryBlobStore()
        val first = WrappedDatabaseKey(blobs, FakeCipher()).passphrase()
        val second = WrappedDatabaseKey(blobs, FakeCipher()).passphrase()

        assertEquals(first, second)
        assertEquals(1, blobs.writes)
    }

    @Test
    fun storesTheKeyWrappedNotInTheClear() {
        val blobs = MemoryBlobStore()

        val passphrase = WrappedDatabaseKey(blobs, FakeCipher()).passphrase()

        val rawKey = Base64.getDecoder().decode(passphrase)
        assertFalse(blobs.value!!.contains(passphrase))
        assertFalse(SealedBytes.decode(blobs.value!!).ciphertext.contentEquals(rawKey))
        assertNotEquals(passphrase, blobs.value)
    }

    @Test
    fun theStoredBlobCarriesTheInitializationVector() {
        val blobs = MemoryBlobStore()

        WrappedDatabaseKey(blobs, FakeCipher()).passphrase()

        assertArrayEquals(byteArrayOf(7, 7, 7), SealedBytes.decode(blobs.value!!).iv)
    }

    @Test
    fun aKeyOfTheWrongLengthIsRejectedInsteadOfUsed() {
        val blobs = MemoryBlobStore()
        WrappedDatabaseKey(blobs, FakeCipher()).passphrase()
        // A truncated or foreign blob must not silently become a passphrase.
        blobs.value = SealedBytes(byteArrayOf(1), byteArrayOf(2, 3)).encode()

        val wrongCipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): SealedBytes = error("not used")

            override fun decrypt(sealed: SealedBytes): ByteArray = ByteArray(8)
        }

        assertThrows(IllegalStateException::class.java) {
            WrappedDatabaseKey(blobs, wrongCipher).passphrase()
        }
    }

    @Test
    fun aMalformedBlobIsRejected() {
        val blobs = MemoryBlobStore().apply { value = "not-a-sealed-secret" }

        assertThrows(IllegalArgumentException::class.java) {
            WrappedDatabaseKey(blobs, FakeCipher()).passphrase()
        }
    }
}
