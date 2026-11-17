package io.github.lgpy.familycare.core.data.sync

import java.security.SecureRandom
import java.util.Base64

/**
 * Encrypts and decrypts small secrets with a key that never leaves secure
 * storage. The Android implementation wraps the local database key with a
 * non-exportable Android Keystore key; tests use a fake.
 *
 * Isolated behind this interface so the wrapping logic (generate once, store
 * wrapped, unwrap on every start) is plain Kotlin and unit-testable without an
 * Android runtime.
 */
internal interface SecretCipher {

    /** Encrypts [plaintext] with a fresh random IV. */
    fun encrypt(plaintext: ByteArray): SealedBytes

    /** Decrypts [sealed], which must have been produced by [encrypt]. */
    fun decrypt(sealed: SealedBytes): ByteArray
}

/**
 * Ciphertext with the initialization vector it was produced with.
 *
 * Stored base64 as `iv.ciphertext` in a single line, so the blob has no binary
 * encoding surprises when it is read back.
 */
internal data class SealedBytes(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun encode(): String = "${Base64.getEncoder().encodeToString(iv)}.${Base64.getEncoder().encodeToString(ciphertext)}"

    override fun equals(other: Any?): Boolean =
        other is SealedBytes && iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()

    companion object {
        fun decode(value: String): SealedBytes {
            val parts = value.trim().split('.')
            require(parts.size == 2) { "Malformed sealed secret" }
            return SealedBytes(
                iv = Base64.getDecoder().decode(parts[0]),
                ciphertext = Base64.getDecoder().decode(parts[1]),
            )
        }
    }
}

/** Where the wrapped key lives: app-private storage that is never backed up. */
internal interface KeyBlobStore {
    fun read(): String?

    fun write(value: String)
}

/**
 * The 32-byte key that encrypts the local PowerSync database (D13: "phone",
 * security-and-privacy §4).
 *
 * The key is generated once with [SecureRandom], immediately wrapped with
 * [cipher], and only the wrapped blob is persisted. Unwrapping it needs the
 * Keystore key, which is non-exportable and does not require user
 * authentication: reminders and background sync must open the database while
 * the phone is locked.
 *
 * The returned passphrase is handed to SQLite3MultipleCiphers, which derives the
 * actual cipher key from it. It is never logged.
 */
internal class WrappedDatabaseKey(
    private val blobs: KeyBlobStore,
    private val cipher: SecretCipher,
    private val newKey: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
) {

    /** The database passphrase, unwrapping (or creating and wrapping) the key. */
    fun passphrase(): String {
        val stored = blobs.read()
        val key = if (stored == null) {
            val fresh = newKey(KEY_BYTES)
            blobs.write(cipher.encrypt(fresh).encode())
            fresh
        } else {
            cipher.decrypt(SealedBytes.decode(stored))
        }

        check(key.size == KEY_BYTES) { "The local database key has the wrong length" }
        return Base64.getEncoder().encodeToString(key)
    }

    companion object {
        /** AES-256: 32 random bytes. */
        internal const val KEY_BYTES: Int = 32

        /** File name inside `Context.noBackupFilesDir`. */
        internal const val BLOB_FILE_NAME: String = "sync-database-key"
    }
}
