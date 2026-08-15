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
 * material never appears in the preferences file, so backups and ordinary
 * file access see only ciphertext. Android decides whether the key is backed
 * by secure hardware on a given device.
 *
 * Uses Android Keystore and AES-GCM directly because
 * EncryptedSharedPreferences is deprecated.
 *
 * Failure policy: both directions return null instead of throwing. A wiped
 * keystore (rare: OS-level security resets) means the user re-enters the URL,
 * which beats crashing the app on launch.
 */
object SecretStore {
    private const val KEY_ALIAS = "roam_secret_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val FORMAT_PREFIX = "v2:"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val MAX_PLAINTEXT_BYTES = 64 * 1024
    private const val MAX_ENCODED_CHARS = 128 * 1024

    @Synchronized
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

    /** plaintext -> versioned base64(iv || ciphertext), bound to [purpose]. */
    fun encrypt(plain: String, purpose: String): String? = runCatching {
        val plaintext = plain.toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val associatedData = purposeBytes(purpose)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES)
        FORMAT_PREFIX + Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    /** Decode current data and legacy ciphertext written before purpose binding. */
    fun decrypt(encoded: String, purpose: String): String? = runCatching {
        require(encoded.length <= MAX_ENCODED_CHARS)
        val isCurrentFormat = encoded.startsWith(FORMAT_PREFIX)
        val payload = if (isCurrentFormat) encoded.removePrefix(FORMAT_PREFIX) else encoded
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        require(raw.size >= GCM_IV_BYTES + GCM_TAG_BITS / 8)
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (isCurrentFormat) cipher.updateAAD(purposeBytes(purpose))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    fun needsMigration(encoded: String): Boolean = !encoded.startsWith(FORMAT_PREFIX)

    private fun purposeBytes(purpose: String): ByteArray {
        require(purpose.isNotBlank() && purpose.length <= 128)
        return purpose.toByteArray(Charsets.UTF_8)
    }
}
