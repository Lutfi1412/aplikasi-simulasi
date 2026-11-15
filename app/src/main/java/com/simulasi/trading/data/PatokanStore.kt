package com.simulasi.trading.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class Patokan(
    val id: String,
    val value: Double,
    val direction: String, // "below" | "above"
    val status: String,    // "waiting" | "reached"
    val createdAt: Long,
) {
    fun label(): String {
        val n = String.format(Locale("id", "ID"), "%,.2f", value)
        return "${if (direction == "below") "≤" else "≥"} $n"
    }

    fun isReached(price: Double): Boolean =
        if (direction == "below") price <= value else price >= value
}

/**
 * Patokan latar belakang untuk aplikasi mobile. Disimpan di SharedPreferences
 * (bukan localStorage WebView) supaya layanan tetap bisa memeriksanya saat
 * aplikasi tidak dibuka. Boleh lebih dari satu, sama seperti versi web.
 */
object PatokanStore {
    private const val MAX_ITEMS = 50

    private fun parse(raw: String?): List<Patokan>? {
        if (raw == null) return null
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val value = o.optDouble("value", Double.NaN)
                    val direction = o.optString("direction", "below")
                    if (!value.isFinite() || value <= 0) continue
                    if (direction != "below" && direction != "above") continue
                    add(
                        Patokan(
                            id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                            value = value,
                            direction = direction,
                            status = if (o.optString("status") == "reached") "reached" else "waiting",
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        ),
                    )
                }
            }.take(MAX_ITEMS)
        } catch (_: Exception) {
            null
        }
    }

    fun load(c: Context): List<Patokan> = parse(Prefs.patokansRaw(c)) ?: emptyList()

    /**
     * Sinkron dari daftar patokan versi web (localStorage `sim-patokan-list`).
     * Daftar web adalah sumber utama; status "reached" yang sudah pernah berbunyi
     * dipertahankan supaya tidak bunyi ulang saat aplikasi dibuka kembali.
     */
    fun syncFromWeb(c: Context, raw: String) {
        val incoming = parse(raw) ?: return
        val current = load(c).associateBy { it.id }
        val merged = incoming.map { p ->
            val prev = current[p.id]
            if (prev != null &&
                prev.value == p.value &&
                prev.direction == p.direction &&
                prev.status == "reached"
            ) {
                p.copy(status = "reached")
            } else {
                p
            }
        }
        save(c, merged)
    }

    fun save(c: Context, list: List<Patokan>) {
        if (list.isEmpty()) {
            Prefs.setPatokansRaw(c, null)
            return
        }
        Prefs.setPatokansRaw(c, toJson(list))
    }

    /** Serialisasi daftar patokan ke JSON yang sama dengan format web (localStorage). */
    fun toJson(list: List<Patokan>): String {
        val arr = JSONArray()
        for (p in list.take(MAX_ITEMS)) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("value", p.value)
                    put("direction", p.direction)
                    put("status", p.status)
                    put("createdAt", p.createdAt)
                },
            )
        }
        return arr.toString()
    }

    fun update(c: Context, id: String, value: Double, direction: String) =
        save(
            c,
            load(c).map {
                if (it.id == id) it.copy(value = value, direction = direction, status = "waiting") else it
            },
        )

    fun add(c: Context, value: Double, direction: String): Patokan {
        val p = Patokan(
            id = UUID.randomUUID().toString(),
            value = value,
            direction = direction,
            status = "waiting",
            createdAt = System.currentTimeMillis(),
        )
        save(c, listOf(p) + load(c))
        return p
    }

    fun remove(c: Context, id: String) = save(c, load(c).filterNot { it.id == id })

    fun markReached(c: Context, id: String) =
        save(c, load(c).map { if (it.id == id) it.copy(status = "reached") else it })

    fun clearReached(c: Context) = save(c, load(c).filterNot { it.status == "reached" })
}
