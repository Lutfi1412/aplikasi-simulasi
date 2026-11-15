package com.simulasi.trading.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Skema Auto yang bisa diubah sepenuhnya dari aplikasi (tab Binance → "Skema Auto").
 *
 * Semua nilai default = perilaku #28, jadi tanpa diubah Auto tetap seperti sebelumnya.
 * Engine membaca objek ini setiap loop, jadi simpan → berlaku tanpa restart.
 *
 * Tangga (ladder) bisa diisi berapa pun:
 *  - [leverageLadder] leverage entry pertama … leverage setelah lolos cek berikutnya.
 *  - [tpLadder] level TP berurutan (ROI %).
 *  - [slLadder] level SL berurutan; **elemen terakhir = SL keras** yang menempel di exchange.
 */
data class AutoConfig(
    // ---- waktu
    val entryWindowMin: Int = 30,
    val carryMin: Int = 5,
    val recheckMin: Int = 25,

    // ---- izin entry / lanjut
    /** minimal model yang harus selaras arah. */
    val minAgree: Int = 4,
    /** wajib semua model memberi sinyal di window. */
    val requireAllModels: Boolean = true,
    /** dipakai kalau [requireAllModels] mati: minimal model yang menjawab. */
    val minResponded: Int = 6,

    // ---- syarat profit (ROI bot per model)
    val profitRuleEnabled: Boolean = true,
    /** minimal ROI untuk model yang selaras. */
    val alignedMinRoi: Double = 48.0,
    /** berapa model selaras yang wajib mencapai [alignedMinRoi]. */
    val alignedMinCount: Int = 2,
    /** batas ROI tertinggi untuk model yang tidak selaras (harus ≤ nilai ini). */
    val misalignedMaxRoi: Double = -35.0,
    /** model yang belum bersinyal tapi ROI-nya tinggi ikut memblokir. */
    val silentProfitBlockEnabled: Boolean = false,
    val silentProfitMax: Double = 50.0,

    // ---- aturan gap ROI 1-vs-sisanya
    val gapRuleEnabled: Boolean = true,
    val gapPct: Double = 22.0,
    /** gap hanya berlaku kalau ROI model teratas positif. */
    val gapOnlyIfTopPositive: Boolean = true,

    // ---- berbalik arah
    val reversalEnabled: Boolean = true,
    val reversalMin: Int = 4,

    // ---- tangga leverage / TP / SL
    val leverageLadder: List<Int> = listOf(30, 35, 40),
    /** kenaikan leverage kalau tangga sudah habis (dibatasi [maxLeverage]). */
    val leverageStep: Int = 5,
    val maxLeverage: Int = 40,
    val tpLadder: List<Double> = listOf(22.0, 34.0, 50.0, 65.0),
    /** kenaikan TP kalau tangga sudah habis. */
    val tpStep: Double = 15.0,
    val slLadder: List<Double> = listOf(22.0, 31.0, 40.0),

    // ---- aksi saat skema gagal
    /** CARRY = bawa sinyal carry; RESET = netral semua; COOLDOWN = netral + jeda. */
    val entryFailAction: String = "CARRY",
    val entryFailCooldownMin: Int = 30,
    /** OUT = tutup posisi; HOLD = pertahankan posisi dan cek ulang sekali lagi. */
    val recheckFailAction: String = "OUT",
) {
    /** SL keras di exchange = level SL terbesar. */
    val hardSlRoi: Double get() = slLadder.maxOrNull() ?: 40.0
    /** Leverage entry pertama. */
    val entryLeverage: Int get() = leverageLadder.minOrNull() ?: 30
    /** Level TP pertama. */
    val entryTp: Double get() = tpLadder.minOrNull() ?: 22.0
    /** Level SL pertama. */
    val entrySl: Double get() = slLadder.minOrNull() ?: 22.0

    fun sanitized(): AutoConfig {
        val levs = leverageLadder.filter { it in 1..125 }.distinct().sorted().ifEmpty { listOf(30) }
        val tps = tpLadder.filter { it > 0.0 && it <= 2000.0 }.distinct().sorted().ifEmpty { listOf(22.0) }
        val sls = slLadder.filter { it > 0.0 && it <= 100.0 }.distinct().sorted().ifEmpty { listOf(40.0) }
        return copy(
            entryWindowMin = entryWindowMin.coerceIn(5, 240),
            carryMin = carryMin.coerceIn(0, 240),
            recheckMin = recheckMin.coerceIn(5, 240),
            minAgree = minAgree.coerceIn(1, 6),
            minResponded = minResponded.coerceIn(1, 6),
            alignedMinRoi = alignedMinRoi.coerceIn(-100.0, 1000.0),
            alignedMinCount = alignedMinCount.coerceIn(1, 6),
            misalignedMaxRoi = misalignedMaxRoi.coerceIn(-100.0, 500.0),
            silentProfitMax = silentProfitMax.coerceIn(-100.0, 1000.0),
            gapPct = gapPct.coerceIn(0.0, 500.0),
            reversalMin = reversalMin.coerceIn(1, 6),
            leverageLadder = levs,
            maxLeverage = maxLeverage.coerceIn(levs.last(), 125),
            leverageStep = leverageStep.coerceIn(1, 50),
            tpLadder = tps,
            tpStep = tpStep.coerceIn(1.0, 500.0),
            slLadder = sls,
            entryFailCooldownMin = entryFailCooldownMin.coerceIn(0, 240),
            entryFailAction = entryFailAction.uppercase().let {
                if (it in setOf("CARRY", "RESET", "COOLDOWN")) it else "CARRY"
            },
            recheckFailAction = recheckFailAction.uppercase().let {
                if (it in setOf("OUT", "HOLD")) it else "OUT"
            },
        )
    }

    fun toJson(): String = JSONObject().apply {
        put("entryWindowMin", entryWindowMin)
        put("carryMin", carryMin)
        put("recheckMin", recheckMin)
        put("minAgree", minAgree)
        put("requireAllModels", requireAllModels)
        put("minResponded", minResponded)
        put("profitRuleEnabled", profitRuleEnabled)
        put("alignedMinRoi", alignedMinRoi)
        put("alignedMinCount", alignedMinCount)
        put("misalignedMaxRoi", misalignedMaxRoi)
        put("silentProfitBlockEnabled", silentProfitBlockEnabled)
        put("silentProfitMax", silentProfitMax)
        put("gapRuleEnabled", gapRuleEnabled)
        put("gapPct", gapPct)
        put("gapOnlyIfTopPositive", gapOnlyIfTopPositive)
        put("reversalEnabled", reversalEnabled)
        put("reversalMin", reversalMin)
        put("leverageLadder", JSONArray(leverageLadder))
        put("leverageStep", leverageStep)
        put("maxLeverage", maxLeverage)
        put("tpLadder", JSONArray(tpLadder))
        put("tpStep", tpStep)
        put("slLadder", JSONArray(slLadder))
        put("entryFailAction", entryFailAction)
        put("entryFailCooldownMin", entryFailCooldownMin)
        put("recheckFailAction", recheckFailAction)
    }.toString()

    companion object {
        val STANDARD = AutoConfig()

        val CONSERVATIVE = AutoConfig(
            minAgree = 5,
            alignedMinRoi = 55.0,
            alignedMinCount = 3,
            misalignedMaxRoi = -40.0,
            gapPct = 25.0,
            reversalMin = 5,
            leverageLadder = listOf(25, 30, 35),
        )

        val AGGRESSIVE = AutoConfig(
            minAgree = 3,
            alignedMinRoi = 40.0,
            alignedMinCount = 2,
            misalignedMaxRoi = -25.0,
            gapPct = 30.0,
            reversalMin = 3,
            leverageLadder = listOf(30, 40, 50),
            maxLeverage = 50,
            tpLadder = listOf(20.0, 40.0, 60.0),
            slLadder = listOf(20.0, 30.0, 45.0),
        )

        fun parseLadderInts(raw: String): List<Int> =
            raw.split(',', ';', ' ')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..125 }
                .distinct()
                .sorted()

        fun parseLadderDoubles(raw: String): List<Double> =
            raw.split(',', ';', ' ')
                .mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }
                .filter { it > 0.0 }
                .distinct()
                .sorted()

        fun fromJson(raw: String?): AutoConfig {
            if (raw.isNullOrBlank()) return STANDARD
            return try {
                val o = JSONObject(raw)
                STANDARD.copy(
                    entryWindowMin = o.optInt("entryWindowMin", 30),
                    carryMin = o.optInt("carryMin", 5),
                    recheckMin = o.optInt("recheckMin", 25),
                    minAgree = o.optInt("minAgree", 4),
                    requireAllModels = o.optBoolean("requireAllModels", true),
                    minResponded = o.optInt("minResponded", 6),
                    profitRuleEnabled = o.optBoolean("profitRuleEnabled", true),
                    alignedMinRoi = o.optDouble("alignedMinRoi", 48.0),
                    alignedMinCount = o.optInt("alignedMinCount", 2),
                    misalignedMaxRoi = o.optDouble("misalignedMaxRoi", -35.0),
                    silentProfitBlockEnabled = o.optBoolean("silentProfitBlockEnabled", false),
                    silentProfitMax = o.optDouble("silentProfitMax", 50.0),
                    gapRuleEnabled = o.optBoolean("gapRuleEnabled", true),
                    gapPct = o.optDouble("gapPct", 22.0),
                    gapOnlyIfTopPositive = o.optBoolean("gapOnlyIfTopPositive", true),
                    reversalEnabled = o.optBoolean("reversalEnabled", true),
                    reversalMin = o.optInt("reversalMin", 4),
                    leverageLadder = intList(o.optJSONArray("leverageLadder"), listOf(30, 35, 40)),
                    leverageStep = o.optInt("leverageStep", 5),
                    maxLeverage = o.optInt("maxLeverage", 40),
                    tpLadder = doubleList(o.optJSONArray("tpLadder"), listOf(22.0, 34.0, 50.0, 65.0)),
                    tpStep = o.optDouble("tpStep", 15.0),
                    slLadder = doubleList(o.optJSONArray("slLadder"), listOf(22.0, 31.0, 40.0)),
                    entryFailAction = o.optString("entryFailAction", "CARRY"),
                    entryFailCooldownMin = o.optInt("entryFailCooldownMin", 30),
                    recheckFailAction = o.optString("recheckFailAction", "OUT"),
                ).sanitized()
            } catch (_: Exception) {
                STANDARD
            }
        }

        private fun intList(a: JSONArray?, fallback: List<Int>): List<Int> {
            if (a == null || a.length() == 0) return fallback
            val out = ArrayList<Int>()
            for (i in 0 until a.length()) out += a.optInt(i)
            return out.ifEmpty { fallback }
        }

        private fun doubleList(a: JSONArray?, fallback: List<Double>): List<Double> {
            if (a == null || a.length() == 0) return fallback
            val out = ArrayList<Double>()
            for (i in 0 until a.length()) out += a.optDouble(i)
            return out.ifEmpty { fallback }
        }
    }
}
