package dev.whitespc.roam.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secret strings (today: the stream URL, which embeds the stream
 * key) with an AES-256-GCM key that lives inside the Android Keystore. The key
 * material never leaves secure hardware, so `adb`, file managers on rooted
 * phones, and backup tools see only ciphertext in the prefs XML.
 *
 * Hand-rolled instead of EncryptedSharedPreferences because Google deprecated
 * that library in 2024 with no replacement; this is the same primitive it used,
 * small enough to own.
 *
 * Failure policy: both directions return null instead of throwing. A wiped
 * keystore (rare: OS-level security resets) means the user re-enters the URL,
 * which beats crashing the app on launch.
 */
object SecretStore {
    private const val KEY_ALIAS = "roam_secret_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** plaintext -> base64(iv || ciphertext), or null if the keystore refuses. */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    /** base64(iv || ciphertext) -> plaintext, or null (wiped key, bad data). */
    fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()
}
