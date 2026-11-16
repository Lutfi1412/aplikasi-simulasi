package com.simulasi.trading.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Satu aturan skema (dipakai untuk skema Entry maupun skema Recheck).
 *
 * Skema hanya berisi **syarat**; waktu, tangga leverage/TP/SL, dan aksi saat gagal ada di
 * [AutoCommon] karena berlaku untuk semua skema.
 *
 * Banyak skema dengan logika **OR**: kalau ada minimal satu skema aktif yang cocok, ACC.
 */
data class SchemeRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,

    // ---- responden
    /** wajib semua model memberi sinyal di window. */
    val requireAllModels: Boolean = true,
    /** dipakai kalau [requireAllModels] mati. */
    val minResponded: Int = 6,

    // ---- selaras
    /** minimal model yang harus selaras arah. */
    val minAgree: Int = 4,

    // ---- syarat profit (ROI bot per model)
    val profitRuleEnabled: Boolean = true,
    val alignedMinRoi: Double = 48.0,
    val alignedMinCount: Int = 2,
    val misalignedMaxRoi: Double = -35.0,

    // ---- model belum bersinyal tapi untung besar
    val silentProfitBlockEnabled: Boolean = false,
    val silentProfitMax: Double = 50.0,

    // ---- gap ROI 1 vs sisanya
    val gapRuleEnabled: Boolean = true,
    val gapPct: Double = 22.0,
    val gapOnlyIfTopPositive: Boolean = true,
) {
    fun sanitized(): SchemeRule = copy(
        minResponded = minResponded.coerceIn(1, 6),
        minAgree = minAgree.coerceIn(1, 6),
        alignedMinRoi = alignedMinRoi.coerceIn(-100.0, 1000.0),
        alignedMinCount = alignedMinCount.coerceIn(1, 6),
        misalignedMaxRoi = misalignedMaxRoi.coerceIn(-100.0, 500.0),
        silentProfitMax = silentProfitMax.coerceIn(-100.0, 1000.0),
        gapPct = gapPct.coerceIn(0.0, 500.0),
        name = name.trim().ifBlank { "Skema" },
    )

    /** Ringkasan satu baris untuk daftar skema. */
    fun summary(): String = buildString {
        append(if (requireAllModels) "6/6" else "≥$minResponded")
        append(" jawab · ≥")
        append(minAgree)
        append("/6 selaras")
        if (profitRuleEnabled) {
            append(" · ")
            append(alignedMinCount)
            append(" ≥")
            append(alignedMinRoi.toInt())
            append("% · lawan ≤")
            append(misalignedMaxRoi.toInt())
            append("%")
        }
        if (gapRuleEnabled) {
            append(" · gap ≥")
            append(gapPct.toInt())
        }
        if (silentProfitBlockEnabled) {
            append(" · blokir diam >")
            append(silentProfitMax.toInt())
            append("%")
        }
    }

    /**
     * Skema ini masih persis nilai bawaan aplikasi (belum pernah diedit)? Dipakai migrasi
     * yang membuang skema bawaan lama supaya Auto hanya memakai skema buatan sendiri.
     */
    fun isUntouchedDefault(): Boolean {
        val d = ENTRY_DEFAULT
        return requireAllModels == d.requireAllModels &&
            minResponded == d.minResponded &&
            minAgree == d.minAgree &&
            profitRuleEnabled == d.profitRuleEnabled &&
            alignedMinRoi == d.alignedMinRoi &&
            alignedMinCount == d.alignedMinCount &&
            misalignedMaxRoi == d.misalignedMaxRoi &&
            silentProfitBlockEnabled == d.silentProfitBlockEnabled &&
            silentProfitMax == d.silentProfitMax &&
            gapRuleEnabled == d.gapRuleEnabled &&
            gapPct == d.gapPct &&
            gapOnlyIfTopPositive == d.gapOnlyIfTopPositive
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("requireAllModels", requireAllModels)
        put("minResponded", minResponded)
        put("minAgree", minAgree)
        put("profitRuleEnabled", profitRuleEnabled)
        put("alignedMinRoi", alignedMinRoi)
        put("alignedMinCount", alignedMinCount)
        put("misalignedMaxRoi", misalignedMaxRoi)
        put("silentProfitBlockEnabled", silentProfitBlockEnabled)
        put("silentProfitMax", silentProfitMax)
        put("gapRuleEnabled", gapRuleEnabled)
        put("gapPct", gapPct)
        put("gapOnlyIfTopPositive", gapOnlyIfTopPositive)
    }

    companion object {
        val ENTRY_DEFAULT = SchemeRule(id = "entry-1", name = "Entry standar", minAgree = 4)
        val RECHECK_DEFAULT = SchemeRule(id = "recheck-1", name = "Recheck standar", minAgree = 4)

        fun fromJson(o: JSONObject?): SchemeRule {
            if (o == null) return ENTRY_DEFAULT
            return ENTRY_DEFAULT.copy(
                id = o.optString("id", "entry-1"),
                name = o.optString("name", "Skema"),
                enabled = o.optBoolean("enabled", true),
                requireAllModels = o.optBoolean("requireAllModels", true),
                minResponded = o.optInt("minResponded", 6),
                minAgree = o.optInt("minAgree", 4),
                profitRuleEnabled = o.optBoolean("profitRuleEnabled", true),
                alignedMinRoi = o.optDouble("alignedMinRoi", 48.0),
                alignedMinCount = o.optInt("alignedMinCount", 2),
                misalignedMaxRoi = o.optDouble("misalignedMaxRoi", -35.0),
                silentProfitBlockEnabled = o.optBoolean("silentProfitBlockEnabled", false),
                silentProfitMax = o.optDouble("silentProfitMax", 50.0),
                gapRuleEnabled = o.optBoolean("gapRuleEnabled", true),
                gapPct = o.optDouble("gapPct", 22.0),
                gapOnlyIfTopPositive = o.optBoolean("gapOnlyIfTopPositive", true),
            ).sanitized()
        }
    }
}

/**
 * Setelan umum Auto: berlaku untuk semua skema.
 *
 * Tangga leverage/TP/SL adalah **patokan (checkpoint)**, bukan order TP/SL sungguhan.
 * Menyentuh level hanya memicu cek model; posisi tetap terbuka.
 *
 *  - [tpExtend] ON  → setelah angka terakhir, patokan TP naik terus +[tpStep] (tidak ada TP asli).
 *    OFF → angka terakhir = **TP asli**: menyentuhnya langsung menutup posisi.
 *  - [slExtend] ON  → patokan SL melebar terus +[slStep] (tidak ada batas rugi asli).
 *    OFF → angka terakhir = **SL asli**: menyentuhnya menutup posisi (dan itu SL keras di exchange).
 */
data class AutoCommon(
    // ---- waktu
    val entryWindowMin: Int = 30,
    val carryMin: Int = 5,
    val recheckMin: Int = 25,

    // ---- tangga leverage
    val leverageLadder: List<Int> = listOf(30, 35, 40),
    val leverageStep: Int = 5,
    val maxLeverage: Int = 40,

    // ---- tangga TP (patokan)
    val tpLadder: List<Double> = listOf(22.0, 34.0, 50.0, 65.0),
    val tpStep: Double = 15.0,
    val tpExtend: Boolean = true,

    // ---- tangga SL (patokan)
    val slLadder: List<Double> = listOf(22.0, 31.0, 40.0),
    val slStep: Double = 10.0,
    val slExtend: Boolean = false,

    // ---- aksi saat skema gagal
    val entryFailAction: String = "CARRY",
    val entryFailCooldownMin: Int = 30,
    val recheckFailAction: String = "OUT",

    // ---- berbalik arah (global, dicek sebelum skema recheck)
    val reversalEnabled: Boolean = true,
    val reversalMin: Int = 4,

    /**
     * Selama Auto ON, Home dipaksa netral berkala setiap N detik supaya tidak ada model
     * yang "ditangguhkan" setelah Auto ON. 0 = matikan.
     */
    val homeNeutralSec: Int = 60,

    // ---- daftar skema (kosong = Auto tidak entry/lanjut sampai Anda menambah skema)
    val entrySchemes: List<SchemeRule> = emptyList(),
    val recheckSchemes: List<SchemeRule> = emptyList(),
) {
    /** Leverage entry pertama. */
    val entryLeverage: Int get() = leverageLadder.minOrNull() ?: 30
    /** Patokan TP pertama. */
    val entryTp: Double get() = tpLadder.minOrNull() ?: 22.0
    /** Patokan SL pertama. */
    val entrySl: Double get() = slLadder.minOrNull() ?: 22.0
    /** Level SL terdalam (selalu dipasang sebagai SL keras di exchange). */
    val maxSl: Double get() = slLadder.maxOrNull() ?: 40.0

    val activeEntrySchemes: List<SchemeRule> get() = entrySchemes.filter { it.enabled }
    val activeRecheckSchemes: List<SchemeRule> get() = recheckSchemes.filter { it.enabled }

    fun sanitized(): AutoCommon {
        val levs = leverageLadder.filter { it in 1..125 }.distinct().sorted().ifEmpty { listOf(30) }
        val tps = tpLadder.filter { it > 0.0 && it <= 5000.0 }.distinct().sorted().ifEmpty { listOf(22.0) }
        val sls = slLadder.filter { it > 0.0 && it <= 100.0 }.distinct().sorted().ifEmpty { listOf(40.0) }
        return copy(
            entryWindowMin = entryWindowMin.coerceIn(5, 240),
            carryMin = carryMin.coerceIn(0, 240),
            recheckMin = recheckMin.coerceIn(5, 240),
            leverageLadder = levs,
            maxLeverage = maxLeverage.coerceIn(levs.last(), 125),
            leverageStep = leverageStep.coerceIn(1, 50),
            tpLadder = tps,
            tpStep = tpStep.coerceIn(1.0, 500.0),
            slLadder = sls,
            slStep = slStep.coerceIn(1.0, 100.0),
            entryFailCooldownMin = entryFailCooldownMin.coerceIn(0, 240),
            entryFailAction = entryFailAction.uppercase().let {
                if (it in setOf("CARRY", "RESET", "COOLDOWN")) it else "CARRY"
            },
            recheckFailAction = recheckFailAction.uppercase().let {
                if (it in setOf("OUT", "HOLD")) it else "OUT"
            },
            reversalMin = reversalMin.coerceIn(1, 6),
            homeNeutralSec = homeNeutralSec.coerceIn(0, 600),
            entrySchemes = entrySchemes.map { it.sanitized() },
            recheckSchemes = recheckSchemes.map { it.sanitized() },
        )
    }

    fun toJson(): String = JSONObject().apply {
        put("entryWindowMin", entryWindowMin)
        put("carryMin", carryMin)
        put("recheckMin", recheckMin)
        put("leverageLadder", JSONArray(leverageLadder))
        put("leverageStep", leverageStep)
        put("maxLeverage", maxLeverage)
        put("tpLadder", JSONArray(tpLadder))
        put("tpStep", tpStep)
        put("tpExtend", tpExtend)
        put("slLadder", JSONArray(slLadder))
        put("slStep", slStep)
        put("slExtend", slExtend)
        put("entryFailAction", entryFailAction)
        put("entryFailCooldownMin", entryFailCooldownMin)
        put("recheckFailAction", recheckFailAction)
        put("reversalEnabled", reversalEnabled)
        put("reversalMin", reversalMin)
        put("homeNeutralSec", homeNeutralSec)
        put("entrySchemes", JSONArray(entrySchemes.map { it.toJson() }))
        put("recheckSchemes", JSONArray(recheckSchemes.map { it.toJson() }))
    }.toString()

    companion object {
        val STANDARD = AutoCommon()

        /** Angka umum yang lebih ketat; **daftar skema tidak diubah** (skema milik Anda). */
        val CONSERVATIVE = AutoCommon(
            leverageLadder = listOf(25, 30, 35),
            reversalMin = 5,
        )

        /** Angka umum yang lebih longgar; **daftar skema tidak diubah** (skema milik Anda). */
        val AGGRESSIVE = AutoCommon(
            leverageLadder = listOf(30, 40, 50),
            maxLeverage = 50,
            tpLadder = listOf(20.0, 40.0, 60.0),
            slLadder = listOf(20.0, 30.0, 45.0),
            reversalMin = 3,
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

        fun fromJson(raw: String?): AutoCommon {
            if (raw.isNullOrBlank()) return STANDARD
            return try {
                val o = JSONObject(raw)
                // Format lama (#29) menyimpan syarat langsung di root; migrasi ke skema tunggal.
                val legacyScheme = if (!o.has("entrySchemes")) {
                    SchemeRule.ENTRY_DEFAULT.copy(
                        requireAllModels = o.optBoolean("requireAllModels", true),
                        minResponded = o.optInt("minResponded", 6),
                        minAgree = o.optInt("minAgree", 4),
                        profitRuleEnabled = o.optBoolean("profitRuleEnabled", true),
                        alignedMinRoi = o.optDouble("alignedMinRoi", 48.0),
                        alignedMinCount = o.optInt("alignedMinCount", 2),
                        misalignedMaxRoi = o.optDouble("misalignedMaxRoi", -35.0),
                        silentProfitBlockEnabled = o.optBoolean("silentProfitBlockEnabled", false),
                        silentProfitMax = o.optDouble("silentProfitMax", 50.0),
                        gapRuleEnabled = o.optBoolean("gapRuleEnabled", true),
                        gapPct = o.optDouble("gapPct", 22.0),
                        gapOnlyIfTopPositive = o.optBoolean("gapOnlyIfTopPositive", true),
                    )
                } else {
                    null
                }
                STANDARD.copy(
                    entryWindowMin = o.optInt("entryWindowMin", 30),
                    carryMin = o.optInt("carryMin", 5),
                    recheckMin = o.optInt("recheckMin", 25),
                    leverageLadder = intList(o.optJSONArray("leverageLadder"), listOf(30, 35, 40)),
                    leverageStep = o.optInt("leverageStep", 5),
                    maxLeverage = o.optInt("maxLeverage", 40),
                    tpLadder = doubleList(o.optJSONArray("tpLadder"), listOf(22.0, 34.0, 50.0, 65.0)),
                    tpStep = o.optDouble("tpStep", 15.0),
                    tpExtend = o.optBoolean("tpExtend", true),
                    slLadder = doubleList(o.optJSONArray("slLadder"), listOf(22.0, 31.0, 40.0)),
                    slStep = o.optDouble("slStep", 10.0),
                    slExtend = o.optBoolean("slExtend", false),
                    entryFailAction = o.optString("entryFailAction", "CARRY"),
                    entryFailCooldownMin = o.optInt("entryFailCooldownMin", 30),
                    recheckFailAction = o.optString("recheckFailAction", "OUT"),
                    reversalEnabled = o.optBoolean("reversalEnabled", true),
                    reversalMin = o.optInt("reversalMin", 4),
                    homeNeutralSec = o.optInt("homeNeutralSec", 60),
                    entrySchemes = schemes(o.optJSONArray("entrySchemes"), legacyScheme, SchemeRule.ENTRY_DEFAULT),
                    recheckSchemes = schemes(
                        o.optJSONArray("recheckSchemes"), legacyScheme, SchemeRule.RECHECK_DEFAULT,
                    ),
                ).sanitized()
            } catch (_: Exception) {
                STANDARD
            }
        }

        /**
         * Daftar skema tersimpan. **Daftar kosong dihormati** (Auto tidak entry sampai skema
         * dibuat sendiri); skema default hanya dipakai untuk memigrasi config format lama.
         *
         * Skema bawaan aplikasi yang belum pernah diedit (id + nama + semua syarat masih
         * default) dibuang, supaya Auto hanya memakai skema buatan Anda sendiri.
         */
        private fun schemes(a: JSONArray?, legacy: SchemeRule?, fallback: SchemeRule): List<SchemeRule> {
            if (a == null) {
                return listOf(legacy?.copy(id = fallback.id, name = fallback.name) ?: fallback)
            }
            val out = ArrayList<SchemeRule>()
            for (i in 0 until a.length()) {
                val s = SchemeRule.fromJson(a.optJSONObject(i))
                val stock = s.id in setOf("entry-1", "recheck-1") &&
                    s.name in setOf("Entry standar", "Recheck standar") &&
                    s.isUntouchedDefault()
                if (!stock) out += s
            }
            return out
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
