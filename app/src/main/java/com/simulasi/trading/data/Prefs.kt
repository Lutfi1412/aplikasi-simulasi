package com.simulasi.trading.data

import android.content.Context

/** Semua pengaturan aplikasi. Default: layanan latar belakang AKTIF setelah aplikasi dibuka. */
object Prefs {
    const val DEFAULT_BASE_URL = "https://marking-lit-alice-sustainable.trycloudflare.com"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("simulasi_prefs", Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String {
        val raw = sp(c).getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        return raw.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
    }

    fun setBaseUrl(c: Context, value: String) =
        sp(c).edit().putString("base_url", value.trim().trimEnd('/')).apply()

    fun isEnabled(c: Context): Boolean = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, value: Boolean) = sp(c).edit().putBoolean("enabled", value).apply()

    fun soundEnabled(c: Context): Boolean = sp(c).getBoolean("sound_enabled", true)
    fun setSoundEnabled(c: Context, value: Boolean) =
        sp(c).edit().putBoolean("sound_enabled", value).apply()

    /** true saat MainActivity terlihat; layanan latar belakang tidak membunyikan notifikasi ganda. */
    fun appVisible(c: Context): Boolean = sp(c).getBoolean("app_visible", false)
    fun setAppVisible(c: Context, value: Boolean) =
        sp(c).edit().putBoolean("app_visible", value).apply()

    fun isSeeded(c: Context): Boolean = sp(c).getBoolean("signal_seeded", false)
    fun setSeeded(c: Context, value: Boolean) = sp(c).edit().putBoolean("signal_seeded", value).apply()

    fun signalKeys(c: Context): Set<String> =
        sp(c).getStringSet("signal_keys", emptySet())?.toSet() ?: emptySet()

    fun setSignalKeys(c: Context, keys: Set<String>) =
        sp(c).edit().putStringSet("signal_keys", keys).apply()

    fun patokansRaw(c: Context): String? = sp(c).getString("patokans", null)
    fun setPatokansRaw(c: Context, value: String?) =
        sp(c).edit().putString("patokans", value).apply()

    fun lastPrice(c: Context): Double = sp(c).getFloat("last_price", 0f).toDouble()
    fun setLastPrice(c: Context, value: Double) =
        sp(c).edit().putFloat("last_price", value.toFloat()).apply()
}
