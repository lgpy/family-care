package io.github.lgpy.familycare.core.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the local database key with a key held by the Android Keystore.
 *
 * The wrapping key is AES-256-GCM, generated on first use, non-exportable, and
 * **not** tied to user authentication: the app must be able to open the
 * database while the phone is locked so reminders and background sync keep
 * working (security-and-privacy §4).
 *
 * Nothing here logs key material or ciphertext.
 */
internal class AndroidKeystoreCipher(
    private val alias: String,
) : SecretCipher {

    override fun encrypt(plaintext: ByteArray): SealedBytes {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // ENCRYPT_MODE generates a fresh IV, which is stored with the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return SealedBytes(iv = cipher.iv, ciphertext = cipher.doFinal(plaintext))
    }

    override fun decrypt(sealed: SealedBytes): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, sealed.iv))
        return cipher.doFinal(sealed.ciphertext)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(WRAPPING_KEY_BITS)
                // Explicit, even though it is the default: the key must work
                // while the device is locked.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val WRAPPING_KEY_BITS = 256
    }
}

/**
 * Stores the wrapped key in a file under `Context.noBackupFilesDir`, so it is
 * never copied off the device by Android's backup (the Keystore key could not
 * decrypt it elsewhere anyway).
 */
internal class FileKeyBlobStore(
    private val file: File,
) : KeyBlobStore {

    override fun read(): String? {
        if (!file.exists()) return null
        return file.readText().trim().ifEmpty { null }
    }

    override fun write(value: String) {
        file.parentFile?.mkdirs()
        file.writeText(value)
    }
}
