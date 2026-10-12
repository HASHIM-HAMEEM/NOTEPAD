package com.hashim.notepad.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption: the server only ever sees AES-256-GCM ciphertext.
 * The key is derived on-device from the user's passphrase (PBKDF2-HMAC-SHA256, 210k rounds);
 * the salt and a key-check blob are shared through `device_keys` so other devices can derive the same key.
 */
object Crypto {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    const val KEY_CHECK_PLAINTEXT = "notepad-key-check-v1"
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)).encoded

    /** IV || ciphertext+tag */
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    fun encryptText(key: ByteArray, text: String) = encrypt(key, text.toByteArray(Charsets.UTF_8))
    fun decryptText(key: ByteArray, blob: ByteArray) = String(decrypt(key, blob), Charsets.UTF_8)

    fun keyCheck(key: ByteArray) = encryptText(key, KEY_CHECK_PLAINTEXT)
    fun verifyKey(key: ByteArray, check: ByteArray) = runCatching { decryptText(key, check) == KEY_CHECK_PLAINTEXT }.getOrDefault(false)
}
