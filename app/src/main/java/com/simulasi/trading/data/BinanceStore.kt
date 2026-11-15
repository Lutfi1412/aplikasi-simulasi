package com.simulasi.trading.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BinanceCredentials(
    val apiKey: String,
    val secretKey: String,
    val comment: String,
)

/**
 * Penyimpanan kredensial Binance khusus aplikasi.
 *
 * API key & secret dienkripsi AES/GCM dengan kunci yang dibuat di Android Keystore
 * (tidak bisa dibaca aplikasi lain, tidak ikut backup biasa). Komentar disimpan biasa.
 */
object BinanceStore {
    private const val PREFS = "simulasi_binance"
    private const val KEY_ALIAS = "simulasi_binance_aes"

    private const val K_API = "api_key_enc"
    private const val K_SECRET = "secret_key_enc"
    private const val K_COMMENT = "comment"
    private const val K_API_ON = "api_enabled"
    private const val K_AUTO_ON = "auto_enabled"
    private const val K_SIZING = "sizing_override"
    private const val K_STATE = "auto_state"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveCredentials(c: Context, apiKey: String, secretKey: String, comment: String): Boolean {
        val encKey = encrypt(apiKey)
        val encSecret = encrypt(secretKey)
        if (encKey == null || encSecret == null) return false
        sp(c).edit()
            .putString(K_API, encKey)
            .putString(K_SECRET, encSecret)
            .putString(K_COMMENT, comment)
            .apply()
        return true
    }

    fun loadCredentials(c: Context): BinanceCredentials? {
        val p = sp(c)
        val apiKey = decrypt(p.getString(K_API, null)) ?: return null
        val secret = decrypt(p.getString(K_SECRET, null)) ?: return null
        if (apiKey.isBlank() || secret.isBlank()) return null
        return BinanceCredentials(apiKey, secret, p.getString(K_COMMENT, "").orEmpty())
    }

    fun comment(c: Context): String = sp(c).getString(K_COMMENT, "").orEmpty()

    fun clearCredentials(c: Context) {
        sp(c).edit()
            .remove(K_API)
            .remove(K_SECRET)
            .remove(K_COMMENT)
            .putBoolean(K_API_ON, false)
            .putBoolean(K_AUTO_ON, false)
            .remove(K_STATE)
            .apply()
    }

    fun apiEnabled(c: Context): Boolean = sp(c).getBoolean(K_API_ON, false)

    fun setApiEnabled(c: Context, value: Boolean) {
        val e = sp(c).edit().putBoolean(K_API_ON, value)
        if (!value) e.putBoolean(K_AUTO_ON, false)
        e.apply()
    }

    fun autoEnabled(c: Context): Boolean =
        sp(c).getBoolean(K_AUTO_ON, false) && apiEnabled(c) && loadCredentials(c) != null

    fun setAutoEnabled(c: Context, value: Boolean) {
        val e = sp(c).edit()
        if (value) e.putBoolean(K_API_ON, true)
        e.putBoolean(K_AUTO_ON, value).apply()
    }

    /** Persen margin entry otomatis. Kosong = pakai aturan 50/25/100. */
    fun sizingOverride(c: Context): String = sp(c).getString(K_SIZING, "").orEmpty()
    fun setSizingOverride(c: Context, value: String) =
        sp(c).edit().putString(K_SIZING, value.trim()).apply()

    fun autoState(c: Context): String? = sp(c).getString(K_STATE, null)
    fun setAutoState(c: Context, value: String) =
        sp(c).edit().putString(K_STATE, value).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
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

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + body, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    private fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            if (all.size <= 12) return null
            val iv = all.copyOfRange(0, 12)
            val body = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
