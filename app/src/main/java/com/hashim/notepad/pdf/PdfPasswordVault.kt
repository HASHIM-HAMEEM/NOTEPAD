package com.hashim.notepad.pdf

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hashim.notepad.sync.Crypto
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class PdfPasswordVault(context: Context) {
    private val prefs = context.getSharedPreferences("notepad.pdf-passwords", Context.MODE_PRIVATE)

    fun save(assetId: String, password: String) {
        prefs.edit().putString(assetId, Base64.encodeToString(Crypto.encryptText(masterKey(), password), Base64.NO_WRAP)).apply()
    }

    fun read(assetId: String): String? = prefs.getString(assetId, null)?.let { encoded ->
        runCatching { Crypto.decryptText(masterKey(), Base64.decode(encoded, Base64.NO_WRAP)) }.getOrNull()
    }

    private fun masterKey(): ByteArray {
        val wrapped = prefs.getString(MASTER_KEY, null)
        if (wrapped != null) {
            val blob = Base64.decode(wrapped, Base64.NO_WRAP)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, 12))
                doFinal(blob, 12, blob.size - 12)
            }
        }
        val key = Crypto.randomBytes(32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        prefs.edit().putString(MASTER_KEY, Base64.encodeToString(cipher.iv + cipher.doFinal(key), Base64.NO_WRAP)).apply()
        return key
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "notepad.pdf-password-v1"
        const val MASTER_KEY = "_wrapped_master_key"
    }
}
