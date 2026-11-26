package com.healthvectors.fitness.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the SQLCipher passphrase. A random 256-bit passphrase is generated once, wrapped with an
 * AES-GCM key that lives in the Android Keystore (never leaves secure hardware), and the wrapped value
 * is stored in app-private preferences. The plaintext passphrase exists only in memory.
 */
@Singleton
class DbKeyProvider @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("db_key", Context.MODE_PRIVATE)

    fun passphrase(): ByteArray = secret("wrapped")

    /** A separate 256-bit key for progress photo files, protected the same way. */
    fun photoKey(): ByteArray = secret("photo_wrapped")

    @Synchronized
    private fun secret(pref: String): ByteArray {
        val stored = prefs.getString(pref, null)
        return if (stored != null) unwrap(Base64.decode(stored, Base64.NO_WRAP)) else create(pref)
    }

    // commit, not apply: the key must be on disk before anything is encrypted with it, or a crash right after could
    // leave data that no key opens.
    @android.annotation.SuppressLint("ApplySharedPref")
    private fun create(pref: String): ByteArray {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val blob = cipher.iv + cipher.doFinal(raw)
        prefs.edit().putString(pref, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        return raw
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, 12)
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "fitness_db_wrap_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
