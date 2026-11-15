package com.simulasi.trading.binance

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.simulasi.trading.data.AutoConfig
import com.simulasi.trading.data.BinanceCredentials
import com.simulasi.trading.data.BinanceState
import com.simulasi.trading.data.BinanceStore
import com.simulasi.trading.data.Prefs
import com.simulasi.trading.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Eksekutor Binance + aturan automation yang hanya berjalan di aplikasi.
 *
 * Pembagian tugas (revisi #28, aturan final):
 *  - Sinyal 6 model dikumpulkan dalam window **30 menit**. Skema izin entry/lanjut:
 *    semua 6 menjawab, **≥4 selaras**, **≥2 model selaras ROI ≥48%**, dan **semua model
 *    tidak selaras ROI ≤35%**. Kurang satu syarat = izin 4/6 batal.
 *  - Window gagal (tidak lengkap atau skema batal) → sinyal 5 menit terakhir (menit 25–30)
 *    dibawa sebagai carry, model lain dipaksa netral, window 30 menit baru langsung mulai.
 *    Tidak ada fase tunggu/koreksi terpisah — engine berputar terus.
 *  - Patokan +22% / +34% / +50% / +15% berikutnya dan -22% / -31% tetap **level di aplikasi**.
 *    Saat tersentuh, posisi TETAP terbuka; 6 model dinetralkan dan dinilai ulang maksimal
 *    25 menit. Lolos skema → lanjut (leverage naik), gagal → keluar. Kalau ≥4 model berbalik
 *    arah, posisi lama ditutup dan order baru dibuka di arah berbalik (30x, level fresh).
 *  - Di exchange hanya dipasang **SL keras -40% ROI** (closePosition) sebagai pengaman
 *    terakhir kalau aplikasi/HP mati. Tidak ada order TP di exchange.
 *
 * Sumber sinyal: /api/snapshot (6 model, sinyal per menit + roiPct untuk skema profit/gap).
 * Kredensial: BinanceStore (Android Keystore). Tidak ada kunci yang dikirim ke VPS.
 */
class AutoEngine(private val ctx: Context) {

    companion object {
        @Volatile var instance: AutoEngine? = null

        private const val SYMBOL = "SOLUSDT"
        private const val SNAPSHOT_EVERY_MS = 30_000L
        private const val PROTECT_CHECK_MS = 30_000L

        private val DEFAULT_MODELS = listOf(
            "Aturan dominasi",
            "Regresi Logistik",
            "XGBoost",
            "Meta-label",
            "GRU",
            "Ensemble",
        )
    }

    private enum class Phase { IDLE, BATCH, HOLDING, RECHECK }
    private data class Sig(val side: Int, val ts: Long)
    private data class ModelSignal(val name: String, val side: Int)
    private data class BotRoi(val model: String, val roiPct: Double)
    private data class SnapshotData(
        val price: Double,
        val models: List<ModelSignal>,
        val bots: List<BotRoi>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var job: Job? = null
    private var api: BinanceApi? = null
    private var apiFingerprint = ""
    private var lastSnapshot: SnapshotData? = null
    private var lastSnapshotAt = 0L
    private var lastAccount: BinanceAccount? = null
    private var lastMark = 0.0
    private var serverNeutralAt = 0L

    private var phase = Phase.IDLE
    private var batchStart = 0L
    private val batch = LinkedHashMap<String, Sig>()
    private val latest = LinkedHashMap<String, Sig>()
    private val current = LinkedHashMap<String, Int>()
    private val lastSeen = LinkedHashMap<String, Long>()
    /** Sinyal yang terkumpul selama window cek 25 menit (menit 0 = patokan tersentuh). */
    private val recheckSigs = LinkedHashMap<String, Sig>()
    /** Waktu snapshot terakhir saat patokan tersentuh; sinyal yang lebih lama tidak dihitung. */
    private var recheckSnapshotAt = 0L
    /**
     * Timestamp sinyal carry saat window digulirkan. Sinyal dengan `ts <= batchCarryAt`
     * adalah warisan window sebelumnya, bukan jawaban window berjalan.
     */
    private var batchCarryAt = 0L
    private var seeded = false
    private var modelNames: List<String> = DEFAULT_MODELS

    private var direction = 0
    private var leverage = AutoConfig.STANDARD.entryLeverage
    /** level patokan naik berikutnya (ROI %) dan turun berikutnya (ROI %) */
    private var tpLevel = AutoConfig.STANDARD.entryTp
    private var slLevel = AutoConfig.STANDARD.entrySl
    private var nextLeverage = AutoConfig.STANDARD.entryLeverage
    private var nextTp = AutoConfig.STANDARD.entryTp
    private var nextSl = AutoConfig.STANDARD.entrySl

    private var recheckStart = 0L
    private var recheckUntil = 0L
    /** Saat aksi COOLDOWN dipilih: tidak boleh entry sampai waktu ini. */
    private var cooldownUntil = 0L

    /**
     * Selama cek 25 menit: posisi Binance TETAP terbuka (masih trading). Yang dinetralkan
     * hanya keenam model. `recheckKind` menyimpan level mana yang tersentuh (TP/SL) untuk
     * ditampilkan di panel Auto.
     */
    private var recheckKind = ""

    private var entryPrice = 0.0
    private var hardSlPrice = 0.0
    private var lastRoi = 0.0
    private var lastProtectCheck = 0L
    private var stateLoaded = false
    private var lastAutoFlag = false

    /**
     * Skema Auto yang aktif. Dibaca ulang dari [BinanceStore] setiap loop supaya perubahan
     * dari tab Binance langsung berlaku tanpa restart layanan.
     */
    private var cfg = AutoConfig.STANDARD

    private val entryWindowMs: Long get() = cfg.entryWindowMin * 60_000L
    private val carryMs: Long get() = cfg.carryMin * 60_000L
    private val recheckMs: Long get() = cfg.recheckMin * 60_000L
    private val hardSlRoi: Double get() = cfg.hardSlRoi

    fun start() {
        if (job?.isActive == true) return
        instance = this
        job = scope.launch {
            cfg = BinanceStore.autoConfig(ctx)
            loadState()
            while (isActive) {
                try {
                    loopOnce()
                } catch (e: Exception) {
                    fail(e.message ?: e.javaClass.simpleName)
                }
                delay(if (BinanceStore.autoEnabled(ctx)) 3_000L else 5_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        api = null
        if (instance === this) instance = null
    }

    /**
     * Auto ON: semua model di Home dikembalikan ke netral (memantau posisinya masing-masing),
     * lalu engine mulai dari pola baru. Netral di server tidak mengubah modal/statistik.
     */
    fun onAutoEnabled() {
        lastAutoFlag = true
        phase = Phase.IDLE
        clearState()
        seeded = false
        saveState()
        log("Auto ON — semua model dinetralkan, mulai mencari pola baru")
        ui {
            BinanceState.autoPhase = "IDLE"
            BinanceState.autoDetail = "Menunggu pola 4/6 · window 30 menit"
        }
        neutralizeAllModels("Auto ON")
    }

    fun onAutoDisabled() {
        lastAutoFlag = false
        phase = Phase.IDLE
        saveState()
        log("Auto OFF — posisi (jika ada) tetap terlindungi SL -40% di exchange")
        ui {
            BinanceState.autoPhase = "OFF"
            BinanceState.autoDetail = "Auto mati · API siap manual"
        }
    }

    /**
     * Dipanggil setelah skema Auto disimpan. Engine memuat setelan baru segera; kalau
     * tidak ada posisi, level/leverage dikembalikan ke awal tangga supaya form langsung
     * terasa efeknya.
     */
    fun onConfigChanged() {
        cfg = BinanceStore.autoConfig(ctx)
        if (phase == Phase.IDLE || phase == Phase.BATCH) {
            leverage = cfg.entryLeverage
            tpLevel = cfg.entryTp
            slLevel = cfg.entrySl
            nextLeverage = cfg.entryLeverage
            nextTp = cfg.entryTp
            nextSl = cfg.entrySl
        }
        log(
            "skema diperbarui: window ${cfg.entryWindowMin}m · ≥${cfg.minAgree} selaras · " +
                "lev ${cfg.leverageLadder.joinToString("/")} · TP ${cfg.tpLadder.joinToString("/")} · " +
                "SL ${cfg.slLadder.joinToString("/")}",
        )
        saveState()
        publishAuto()
    }

    /**
     * Semua kejadian "netral" juga menetralkan keenam model simulasi di Home, bukan hanya
     * posisi Binance — sesuai aturan "kalau sudah nyentuh patokan semua model dinetralkan
     * kembali". Dijalankan di latar belakang supaya loop engine tidak tertahan.
     */
    private fun neutralizeAllModels(reason: String) {
        scope.launch {
            neutralizeServerBots(reason)
        }
    }

    /** Panggil endpoint server supaya 6 bot simulasi di Home benar-benar netral. */
    private suspend fun neutralizeServerBots(reason: String = "Auto ON") {
        try {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${Prefs.baseUrl(ctx)}/api/bots/neutralize-all")
                .post(body)
                .header("cache-control", "no-store")
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    log("netral server gagal: HTTP ${resp.code}")
                    return
                }
                val j = runCatching { JSONObject(text) }.getOrNull()
                val n = j?.optInt("neutralized", 0) ?: 0
                val total = j?.optInt("bots", 0) ?: 0
                log(
                    if (n > 0) "server ($reason): $n/$total model dipaksa netral"
                    else "server ($reason): semua model sudah netral",
                )
                serverNeutralAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            log("netral server gagal: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- loop

    private suspend fun loopOnce() {
        cfg = BinanceStore.autoConfig(ctx)
        if (!BinanceStore.apiEnabled(ctx)) {
            ui {
                BinanceState.connected = false
                BinanceState.autoPhase = "OFF"
                BinanceState.autoDetail = "API Binance mati"
            }
            return
        }
        val creds = BinanceStore.loadCredentials(ctx)
        if (creds == null) {
            fail("API key/secret belum diisi")
            return
        }
        val client = ensureApi(creds) ?: return
        val account = try {
            client.account()
        } catch (e: Exception) {
            fail(e.message ?: "gagal mengambil saldo")
            return
        }
        lastAccount = account
        publishAccount(account)

        if (!BinanceStore.autoEnabled(ctx)) {
            lastAutoFlag = false
            ui {
                BinanceState.autoPhase = "OFF"
                BinanceState.autoDetail = "Auto mati · API siap manual"
            }
            return
        }
        val now = System.currentTimeMillis()
        if (!lastAutoFlag && phase == Phase.IDLE) onAutoEnabled()
        lastAutoFlag = true

        val snap = try {
            if (lastSnapshot == null || now - lastSnapshotAt >= SNAPSHOT_EVERY_MS) {
                fetchSnapshot().also {
                    lastSnapshot = it
                    lastSnapshotAt = now
                }
            } else {
                lastSnapshot
            }
        } catch (e: Exception) {
            fail("sinyal: ${e.message}")
            return
        } ?: return

        applySnapshot(snap, now)
        tick(now, account, snap)
    }

    private suspend fun ensureApi(creds: BinanceCredentials): BinanceApi? {
        val fingerprint = creds.apiKey.take(12) + ":" + creds.secretKey.hashCode()
        if (api == null || fingerprint != apiFingerprint) {
            val fresh = BinanceApi(creds)
            api = fresh
            apiFingerprint = fingerprint
            try {
                fresh.syncTime()
                fresh.refreshHedgeMode()
            } catch (e: Exception) {
                fail(e.message ?: "gagal konek ke Binance")
                return null
            }
        }
        return api
    }

    private suspend fun fetchSnapshot(): SnapshotData = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Prefs.baseUrl(ctx)}/api/snapshot")
            .header("cache-control", "no-store")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val j = JSONObject(text)
            val price = j.optJSONObject("market")?.optDouble("price", 0.0) ?: 0.0
            val models = ArrayList<ModelSignal>()
            val ma = j.optJSONArray("models") ?: JSONArray()
            for (i in 0 until ma.length()) {
                val o = ma.optJSONObject(i) ?: continue
                models += ModelSignal(o.optString("name"), o.optInt("signal", 0))
            }
            val bots = ArrayList<BotRoi>()
            val ba = j.optJSONArray("bots") ?: JSONArray()
            for (i in 0 until ba.length()) {
                val o = ba.optJSONObject(i) ?: continue
                bots += BotRoi(o.optString("model"), o.optDouble("roiPct", 0.0))
            }
            SnapshotData(price, models, bots)
        }
    }

    // ------------------------------------------------------------- signals

    private fun applySnapshot(snap: SnapshotData, now: Long) {
        if (snap.models.isNotEmpty()) {
            modelNames = snap.models.map { it.name }
            ui { BinanceState.modelCount = modelNames.size }
        }
        if (!seeded) {
            for (m in snap.models) if (m.side != 0) lastSeen[m.name] = now
            seeded = true
            return
        }

        for (m in snap.models) {
            current[m.name] = m.side
            if (m.side == 0) continue
            val sig = Sig(m.side, now)
            latest[m.name] = sig
            lastSeen[m.name] = now
            when (phase) {
                Phase.IDLE, Phase.BATCH -> {
                    // Window baru selalu dimulai dari sinyal pertama yang masuk.
                    if (batch.isEmpty()) batchStart = now
                    // Sinyal searah yang berulang tetap dianggap aktif (timestamp diperbarui).
                    batch[m.name] = sig
                    if (phase == Phase.IDLE) phase = Phase.BATCH
                }
                Phase.RECHECK -> {
                    // Kumpulkan sepanjang window cek; arah terakhir yang dipakai menilai.
                    // Snapshot yang sudah ada sebelum patokan tersentuh tidak dihitung.
                    if (lastSnapshotAt > recheckSnapshotAt) recheckSigs[m.name] = sig
                }
                else -> {}
            }
        }
    }

    private suspend fun tick(now: Long, account: BinanceAccount, snap: SnapshotData) {
        if (snap.price > 0) lastMark = snap.price
        val pos = account.positions.firstOrNull { it.symbol == SYMBOL }
        when (phase) {
            // posisi hilang saat HOLDING = tertutup SL keras/manual/likuidasi
            Phase.HOLDING -> if (pos == null) onPositionClosedExternally(now) else checkHolding(pos, now)
            // RECHECK: posisi TETAP terbuka; hanya menunggu 25 menit lalu menilai model
            Phase.RECHECK -> if (pos == null) onPositionClosedExternally(now) else continueAfterRecheck(pos, now, snap)
            Phase.BATCH -> if (pos != null) adopt(pos) else evaluateBatch(now, snap)
            Phase.IDLE -> if (pos != null) adopt(pos)
        }
        publishAuto()
    }

    /**
     * Evaluasi window 30 menit. Alur tidak punya fase tunggu terpisah:
     *  - Semua 6 model sudah menjawab **di window ini** → putuskan sekarang (izin 4/6 atau gagal).
     *  - Gagal apa pun → sinyal menit 25–30 dibawa sebagai carry, model lain dipaksa netral,
     *    lalu window 30 menit baru langsung berjalan.
     *  - Window habis → putuskan dengan data yang ada; kalau tidak lengkap → carry.
     */
    private suspend fun evaluateBatch(now: Long, snap: SnapshotData) {
        // Jeda (aksi COOLDOWN) berjalan sebelum window dimulai.
        if (cooldownUntil > 0) {
            if (now < cooldownUntil) return
            cooldownUntil = 0
            saveState()
        }
        val total = modelNames.size.coerceAtLeast(1)
        val deadline = batchStart + entryWindowMs
        val required = requiredResponded(total)
        val allResponded = batch.size >= required
        // Sinyal carry berasal dari window sebelumnya; model harus mengirim sinyal BARU
        // (ts > batchCarryAt) sebelum dianggap menjawab window ini.
        val allFresh = allResponded && batch.values.all { it.ts > batchCarryAt }

        if (allFresh) {
            decideEntry(now, snap, "window ${cfg.entryWindowMin} menit lengkap")
            return
        }
        if (now < deadline) return
        rollWindow(
            if (allResponded) "model belum menjawab ulang di window ini"
            else "hanya ${batch.size}/$total merespon dalam ${cfg.entryWindowMin} menit",
        )
    }

    /** Jumlah model yang wajib menjawab, mengikuti setelan "wajib semua model". */
    private fun requiredResponded(total: Int): Int =
        if (cfg.requireAllModels) total else cfg.minResponded.coerceIn(1, total)

    /**
     * Putuskan izin entry dari sinyal yang terkumpul. Semua ambang dibaca dari [cfg]
     * (form SKEMA AUTO): minimal selaras, syarat profit, gap ROI, dan aksi saat gagal.
     */
    private suspend fun decideEntry(now: Long, snap: SnapshotData, reason: String) {
        val total = modelNames.size.coerceAtLeast(1)
        val counts = countSides(batch)
        val majority = if (counts.longs >= counts.shorts) 1 else -1
        val agree = max(counts.longs, counts.shorts)
        if (agree < cfg.minAgree) {
            failEntry("sepakat hanya $agree/$total (butuh ≥${cfg.minAgree})")
            return
        }
        val profitFail = profitSchemeFail(batch, majority, snap)
        if (profitFail != null) {
            failEntry("skema batal — $profitFail")
            return
        }
        val silentBlock = silentProfitBlock(batch, majority, snap)
        if (silentBlock != null) {
            failEntry("model belum bersinyal tapi ROI tinggi — $silentBlock")
            return
        }
        if (roiGapBlocks(majority, snap)) {
            failEntry("model ROI tertinggi beda arah (gap ≥ ${cfg.gapPct.toInt()}%)")
            return
        }
        doEntry(
            majority, cfg.entryLeverage, cfg.entryTp, cfg.entrySl,
            now, snap, "$reason · $agree/$total",
        )
    }

    /**
     * Aksi saat skema entry gagal, mengikuti setelan aplikasi:
     *  - CARRY: bawa sinyal carry lalu window baru (default, perilaku #28).
     *  - RESET: netralkan semua model, window baru dari nol.
     *  - COOLDOWN: netralkan semua model lalu diam [AutoConfig.entryFailCooldownMin] menit.
     */
    private suspend fun failEntry(reason: String) {
        when (cfg.entryFailAction.uppercase()) {
            "RESET" -> {
                log("$reason — netral semua model, window baru")
                neutralizeAllModels("window gagal")
                resetWindow()
            }
            "COOLDOWN" -> {
                val mins = cfg.entryFailCooldownMin.coerceAtLeast(1)
                log("$reason — netral semua, jeda $mins menit")
                neutralizeAllModels("cooldown entry")
                resetWindow()
                cooldownUntil = System.currentTimeMillis() + mins * 60_000L
                phase = Phase.BATCH
                notify("Skema gagal. Jeda $mins menit.")
                saveState()
            }
            else -> rollWindow(reason)
        }
    }

    /** Kosongkan window dan kembali menunggu sinyal pertama (jeda tidak diubah). */
    private suspend fun resetWindow() {
        batch.clear()
        latest.clear()
        batchStart = 0
        batchCarryAt = 0
        phase = Phase.IDLE
        saveState()
    }

    /**
     * Syarat profit memakai **ROI bot** tiap model dari /api/snapshot
     * (untung/rugi masing-masing model, bukan total gabungan):
     *  - tiap model tidak selaras wajib ROI ≤ [AutoConfig.misalignedMaxRoi] (default −35%);
     *  - minimal [AutoConfig.alignedMinCount] model selaras wajib ROI ≥ [AutoConfig.alignedMinRoi].
     */
    private fun profitSchemeFail(
        sigs: Map<String, Sig>,
        dir: Int,
        snap: SnapshotData,
    ): String? {
        if (!cfg.profitRuleEnabled) return null
        if (snap.bots.isEmpty()) return "ROI model belum tersedia"
        val roiOf = snap.bots.associate { it.model to it.roiPct }
        val aligned = sigs.filterValues { it.side == dir }.keys
        val misaligned = sigs.filterValues { it.side != dir }.keys

        misaligned.forEach { name ->
            val roi = roiOf[name] ?: return "ROI $name belum tersedia"
            if (roi > cfg.misalignedMaxRoi) {
                return "$name tidak selaras ROI ${roi.toInt()}% (harus ≤ ${cfg.misalignedMaxRoi.toInt()}%)"
            }
        }
        val strong = aligned.count { (roiOf[it] ?: 0.0) >= cfg.alignedMinRoi }
        if (strong < cfg.alignedMinCount) {
            return "hanya $strong model selaras ROI ≥${cfg.alignedMinRoi.toInt()}% " +
                "(butuh ${cfg.alignedMinCount})"
        }
        return null
    }

    /**
     * Opsional: model yang **belum bersinyal** di window tapi ROI-nya di atas
     * [AutoConfig.silentProfitMax] ikut memblokir entry (kasus "5/6 selaras tapi yang
     * belum bersinyal sudah untung 50%").
     */
    private fun silentProfitBlock(
        sigs: Map<String, Sig>,
        dir: Int,
        snap: SnapshotData,
    ): String? {
        if (!cfg.silentProfitBlockEnabled) return null
        val silent = modelNames.filter { it !in sigs.keys }
        if (silent.isEmpty()) return null
        val roiOf = snap.bots.associate { it.model to it.roiPct }
        silent.forEach { name ->
            val roi = roiOf[name] ?: return@forEach
            if (roi > cfg.silentProfitMax) {
                return "$name belum bersinyal, ROI ${roi.toInt()}% (> ${cfg.silentProfitMax.toInt()}%)"
            }
        }
        return null
    }

    /** Jumlah model yang berbalik arah melawan posisi (untuk aturan reversal saat recheck). */
    private fun reversalCount(sigs: Map<String, Sig>, dir: Int): Int =
        sigs.values.count { it.side == -dir }

    /**
     * Window gagal: bawa sinyal yang dijawab pada menit terakhir ([AutoConfig.carryMin])
     * sebagai carry, paksa netral model yang menjawab lebih awal, lalu window baru langsung
     * berjalan. Tidak ada jeda kosong.
     */
    private suspend fun rollWindow(reason: String) {
        val keepFrom = batchStart + (entryWindowMs - carryMs)
        val keep = batch.filterValues { it.ts >= keepFrom }
        val dropped = batch.keys - keep.keys

        if (keep.isEmpty()) {
            log("$reason — netral semua model, window ${cfg.entryWindowMin} menit baru")
            neutralizeAllModels("window gagal")
            resetWindow()
            return
        }

        batch.clear()
        batch.putAll(keep)
        batchStart = keep.values.minOf { it.ts }
        batchCarryAt = batchStart
        phase = Phase.BATCH
        if (dropped.isNotEmpty()) neutralizeModels(dropped.toList(), reason)
        log(
            "$reason — ${keep.size} sinyal carry (${cfg.carryMin} menit terakhir) dibawa, " +
                "${dropped.size} model dinetralkan, window ${cfg.entryWindowMin} menit baru",
        )
        saveState()
    }

    /**
     * Dipanggil tiap loop selama fase RECHECK (posisi masih terbuka & masih trading).
     *
     * - SL keras -40% tetap dipasang/dipelihara di exchange.
     * - Patokan berikutnya BELUM berlaku sampai cek 25 menit selesai.
     * - Begitu 6/6 model sudah bersinyal → langsung dinilai (skema 4/6 + syarat profit):
     *   - lolos → LANJUT (leverage naik, level berikutnya);
     *   - **≥4 model berbalik arah** → tutup posisi lama, buka order arah berbalik (30x, level fresh);
     *   - gagal lainnya → keluar total, netral semua, mulai window 30 menit baru.
     * - Sampai batas 25 menit model tidak lengkap → keluar + netral + window baru.
     */
    private suspend fun continueAfterRecheck(pos: BinancePosition, now: Long, snap: SnapshotData) {
        if (entryPrice <= 0 && pos.entryPrice > 0) entryPrice = pos.entryPrice
        if (direction == 0) direction = if (pos.isLong) 1 else -1
        if (leverage <= 0) leverage = pos.leverage.coerceAtLeast(1)

        val client = api ?: return
        if (now - lastProtectCheck >= PROTECT_CHECK_MS) {
            lastProtectCheck = now
            keepHardStop(client, pos)
        }

        val mark = pos.markPrice.takeIf { it > 0 } ?: lastMark
        if (entryPrice > 0 && mark > 0) {
            lastRoi = direction * (mark / entryPrice - 1) * leverage * 100
        }

        val total = modelNames.size.coerceAtLeast(1)
        val responded = recheckSigs.size
        val required = requiredResponded(total)
        if (responded < required) {
            if (now < recheckUntil) return
            recheckFail("cek ${cfg.recheckMin} menit gagal: hanya $responded/$total merespon", now)
            return
        }

        // Sudah cukup model bersinyal → langsung nilai. Arah tiap model = sinyal terakhirnya.
        val reversed = reversalCount(recheckSigs, direction)
        if (cfg.reversalEnabled && reversed >= cfg.reversalMin) {
            reversePosition(reversed, total, now, snap)
            return
        }

        val aligned = recheckSigs.values.count { it.side == direction }
        if (aligned < cfg.minAgree) {
            recheckFail("cek model: hanya $aligned/$total selaras (butuh ≥${cfg.minAgree})", now)
            return
        }
        val profitFail = profitSchemeFail(recheckSigs, direction, snap)
        if (profitFail != null) {
            recheckFail("cek model: skema batal — $profitFail", now)
            return
        }
        if (roiGapBlocks(direction, snap)) {
            recheckFail(
                "cek model: model ROI tertinggi berbalik arah (gap ≥ ${cfg.gapPct.toInt()}%)",
                now,
            )
            return
        }

        // Lolos → lanjutkan posisi yang SAMA, naikkan leverage, pasang patokan berikutnya.
        val newLev = nextLeverage
        val newTp = nextTp
        if (newLev != leverage) {
            try {
                client.setLeverage(SYMBOL, newLev)
                leverage = newLev
                // SL keras dihitung ulang karena leverage berubah (ROI -40% = jarak harga berbeda)
                client.cancelAll(SYMBOL)
                placeHardStop(client, direction, entryPrice, leverage)
            } catch (e: Exception) {
                log("gagal menaikkan leverage: ${e.message}")
            }
        }
        tpLevel = newTp
        slLevel = nextSl
        phase = Phase.HOLDING
        recheckStart = 0
        recheckUntil = 0
        recheckKind = ""
        recheckSigs.clear()
        val dirText = if (direction > 0) "LONG" else "SHORT"
        log(
            "$dirText lanjut (${aligned}/$total selaras, skema lolos) · leverage ${leverage}x · " +
                "patokan berikut +${tpLevel.toInt()}% / -${slLevel.toInt()}%",
        )
        notify("$dirText lanjut ${leverage}x. Patokan +${tpLevel.toInt()}% / -${slLevel.toInt()}%.")
        saveState()
    }

    /**
     * Aksi saat cek gagal, mengikuti setelan aplikasi:
     *  - OUT (default): tutup posisi, netral semua, mulai window baru.
     *  - HOLD: pertahankan posisi dan buka cek baru selama [AutoConfig.recheckMin] menit.
     */
    private suspend fun recheckFail(reason: String, now: Long) {
        if (cfg.recheckFailAction.equals("HOLD", ignoreCase = true)) {
            log("$reason — posisi DITAHAN, cek ${cfg.recheckMin} menit lagi")
            notify("$reason. Posisi ditahan, cek ulang.")
            recheckSigs.clear()
            recheckSnapshotAt = lastSnapshotAt
            recheckStart = now
            recheckUntil = now + recheckMs
            saveState()
            return
        }
        exitAfterRecheck(reason, now)
    }

    /**
     * Recheck gagal dan mayoritas model berbalik arah: jual posisi lama lalu langsung buka
     * order baru di arah berbalik dengan leverage 30x dan level patokan fresh (+22/-22).
     */
    private suspend fun reversePosition(reversed: Int, total: Int, now: Long, snap: SnapshotData) {
        val newDir = -direction
        val dirText = if (newDir > 0) "LONG" else "SHORT"
        log("cek model: $reversed/$total berbalik arah — tutup posisi lama, buka $dirText 30x")
        notify("Model berbalik arah $reversed/$total. Tutup posisi, buka $dirText.")
        busy(true)
        try {
            closeIfOpen(api)
        } finally {
            busy(false)
        }
        waitFlat()
        neutralizeAllModels("berbalik arah")
        clearState()
        phase = Phase.IDLE
        saveState()
        doEntry(newDir, cfg.entryLeverage, cfg.entryTp, cfg.entrySl, now, snap, "reversal $reversed/$total", force = true)
    }

    /** Tunggu posisi benar-benar flat sebelum order arah baru dikirim. */
    private suspend fun waitFlat() {
        val client = api ?: return
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            delay(500)
            val acc = try {
                client.account()
            } catch (_: Exception) {
                null
            } ?: continue
            lastAccount = acc
            publishAccount(acc)
            if (acc.positions.none { it.symbol == SYMBOL }) return
        }
    }

    /** Gagal cek 25 menit: tutup posisi sekarang, netralkan semua model, mulai lagi. */
    private suspend fun exitAfterRecheck(reason: String, now: Long) {
        log("$reason — keluar sekarang")
        notify("$reason. Posisi ditutup.")
        busy(true)
        try {
            closeIfOpen(api)
        } finally {
            busy(false)
        }
        neutralizeAllModels("gagal cek 25 menit")
        phase = Phase.IDLE
        clearState()
        saveState()
    }

    /** Pastikan SL keras masih ada; pasang ulang bila hilang. */
    private fun keepHardStop(client: BinanceApi, pos: BinancePosition) {
        scope.launch {
            try {
                val orders = client.openOrders(SYMBOL)
                if (orders.none { it.optString("type") == "STOP_MARKET" }) {
                    placeHardStop(client, direction, entryPrice, leverage.coerceAtLeast(1))
                    log("SL keras -${hardSlRoi.toInt()}% dipasang ulang")
                }
            } catch (_: Exception) {
            }
        }
    }

    private data class SideCount(val longs: Int, val shorts: Int)

    private fun countSides(sigs: Map<String, Sig>): SideCount {
        val longs = sigs.values.count { it.side > 0 }
        return SideCount(longs, sigs.size - longs)
    }

    /**
     * Paksa netral model tertentu saja (yang tidak membawa carry). Dipakai saat window
     * gagal supaya model yang jawab di bawah menit 25 dikembalikan ke netral, sementara
     * sinyal menit 25–30 tetap dipakai di window berikutnya.
     */
    private fun neutralizeModels(models: List<String>, reason: String) {
        if (models.isEmpty()) return
        scope.launch {
            for (name in models) {
                try {
                    val body = JSONObject().put("id", name).toString()
                        .toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("${Prefs.baseUrl(ctx)}/api/bots/neutral")
                        .post(body)
                        .header("cache-control", "no-store")
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) log("netral $name gagal: HTTP ${resp.code}")
                    }
                } catch (e: Exception) {
                    log("netral $name gagal: ${e.message}")
                }
            }
            log("server ($reason): ${models.size} model dipaksa netral")
        }
    }

    /**
     * Aturan NOTE: kalau model dengan ROI tertinggi berbeda arah dari mayoritas dan
     * jarak ROI-nya ke peringkat kedua ≥ [AutoConfig.gapPct], jangan ikut entry / langsung keluar.
     */
    private fun roiGapBlocks(dir: Int, snap: SnapshotData): Boolean {
        if (!cfg.gapRuleEnabled) return false
        if (snap.bots.size < 2) return false
        val sorted = snap.bots.sortedByDescending { it.roiPct }
        val top = sorted[0]
        val second = sorted[1]
        // Opsional: hanya berlaku kalau model teratas benar-benar profit.
        if (cfg.gapOnlyIfTopPositive && top.roiPct <= 0) return false
        if (top.roiPct - second.roiPct < cfg.gapPct) return false
        // Arah model teratas = sinyal terakhirnya (batch/recheck), bukan stance menit ini.
        val topSide = batch[top.model]?.side
            ?: recheckSigs[top.model]?.side
            ?: latest[top.model]?.side
            ?: current[top.model]
            ?: return false
        if (topSide == 0) return false
        return topSide != dir
    }

    // --------------------------------------------------------------- entry

    private suspend fun doEntry(
        dir: Int,
        lev: Int,
        tpPct: Double,
        slPct: Double,
        now: Long,
        snap: SnapshotData,
        reason: String,
        force: Boolean = false,
    ) {
        if (!force && BinanceState.busy) return
        val client = api ?: return
        val account = lastAccount ?: return
        if (account.positions.any { it.symbol == SYMBOL }) {
            log("masih ada posisi terbuka — entry dibatalkan")
            return
        }
        val livePrice = try {
            client.markPrice(SYMBOL)
        } catch (_: Exception) {
            0.0
        }
        val price = livePrice.takeIf { it > 0 }
            ?: snap.price.takeIf { it > 0 }
            ?: lastMark
        if (price <= 0) {
            fail("harga tidak tersedia")
            return
        }
        val equity = account.walletBalance
        if (equity <= 0) {
            fail("saldo futures kosong")
            return
        }

        val override = BinanceStore.sizingOverride(ctx).toDoubleOrNull()?.takeIf { it in 1.0..100.0 }
        val pct = override ?: when {
            equity >= 55.0 -> 25.0
            equity >= 27.4 -> 50.0
            else -> 100.0
        }
        val margin = min(equity * pct / 100.0, account.availableBalance * 0.98)
        if (margin <= 0.5) {
            fail("margin terlalu kecil (${money(margin)} USDT)")
            return
        }

        busy(true)
        try {
            client.setLeverage(SYMBOL, lev)
            val rules = client.rules(SYMBOL)
            val qtyText = client.formatQty(margin * lev / price, rules)
            val qty = qtyText.toDoubleOrNull() ?: 0.0
            if (qty <= 0 || qty * price < rules.minNotional) {
                throw IllegalStateException("qty di bawah minimum (${rules.minNotional} USDT)")
            }

            val side = if (dir > 0) "BUY" else "SELL"
            val posSide = if (client.hedgeMode) (if (dir > 0) "LONG" else "SHORT") else null
            log("entry ${if (dir > 0) "LONG" else "SHORT"} ${lev}x · margin ${money(margin)} · $reason")
            client.marketOrder(SYMBOL, side, qtyText, clientId("e"), positionSide = posSide)

            val pos = waitForPosition(System.currentTimeMillis() + 8_000)
                ?: throw IllegalStateException("posisi tidak muncul setelah order")
            val entry = pos.entryPrice.takeIf { it > 0 } ?: price
            placeHardStop(client, dir, entry, lev)

            direction = dir
            leverage = lev
            tpLevel = tpPct
            slLevel = slPct
            entryPrice = entry
            lastRoi = 0.0
            phase = Phase.HOLDING
            batch.clear()
            latest.clear()
            recheckSigs.clear()
            seeded = true
            lastProtectCheck = now

            log(
                "posisi ${if (dir > 0) "LONG" else "SHORT"} @ ${priceText(entry)} · " +
                    "patokan app +${tpPct.toInt()}% / -${slPct.toInt()}% · SL keras -${hardSlRoi.toInt()}%",
            )
            notify("Auto ${if (dir > 0) "LONG" else "SHORT"} $lev x · patokan +${tpPct.toInt()}% / -${slPct.toInt()}%")
            saveState()
        } catch (e: Exception) {
            log("entry gagal: ${e.message}")
            try {
                client.cancelAll(SYMBOL)
            } catch (_: Exception) {
            }
            closeIfOpen(client)
            fail(e.message ?: "entry gagal")
        } finally {
            busy(false)
        }
    }

    /**
     * Hanya SL keras di exchange ([AutoConfig.hardSlRoi], default -40% ROI dari entry) —
     * pengaman kalau aplikasi mati. Level +22/+34/+50 dan -22/-31 dikelola engine.
     */
    private suspend fun placeHardStop(client: BinanceApi, dir: Int, entry: Double, lev: Int) {
        val rules = client.rules(SYMBOL)
        val posSide = if (client.hedgeMode) (if (dir > 0) "LONG" else "SHORT") else null
        val exitSide = if (dir > 0) "SELL" else "BUY"
        val sl = if (dir > 0) {
            entry * (1 - hardSlRoi / 100.0 / lev)
        } else {
            entry * (1 + hardSlRoi / 100.0 / lev)
        }
        client.closePositionOrder(
            SYMBOL, exitSide, "STOP_MARKET",
            client.formatPrice(sl, rules), clientId("hs"), posSide,
        )
        hardSlPrice = sl
    }

    private suspend fun waitForPosition(until: Long): BinancePosition? {
        val client = api ?: return null
        while (System.currentTimeMillis() < until) {
            delay(500)
            val acc = try {
                client.account()
            } catch (_: Exception) {
                null
            } ?: continue
            lastAccount = acc
            publishAccount(acc)
            acc.positions.firstOrNull { it.symbol == SYMBOL }?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------- holding

    /**
     * Saat posisi terbuka: pastikan SL keras masih ada, lalu bandingkan ROI terhadap
     * level patokan aplikasi.
     *
     * PENTING: menyentuh patokan TIDAK menutup posisi. Posisi tetap terbuka & masih
     * trading di Binance; yang dinetralkan hanya keenam model, lalu 25 menit kemudian
     * diputuskan lanjut (naik leverage + patokan berikut) atau keluar.
     */
    private suspend fun checkHolding(pos: BinancePosition, now: Long) {
        if (entryPrice <= 0 && pos.entryPrice > 0) entryPrice = pos.entryPrice
        if (direction == 0) direction = if (pos.isLong) 1 else -1
        if (leverage <= 0) leverage = pos.leverage.coerceAtLeast(1)

        val client = api ?: return
        if (now - lastProtectCheck >= PROTECT_CHECK_MS) {
            lastProtectCheck = now
            keepHardStop(client, pos)
        }

        if (entryPrice <= 0) return
        val mark = pos.markPrice.takeIf { it > 0 } ?: lastMark
        if (mark <= 0) return
        val roi = direction * (mark / entryPrice - 1) * leverage * 100
        lastRoi = roi

        if (roi >= tpLevel) {
            startRecheck("TP", roi, now)
        } else if (roi <= -slLevel) {
            startRecheck("SL", roi, now)
        }
    }

    /**
     * Patokan tersentuh → MULAI cek maksimal [AutoConfig.recheckMin] menit. Posisi TIDAK ditutup.
     * Keenam model dinetralkan supaya bisa mencari pola baru; begitu cukup model menjawab,
     * hasilnya dinilai: lanjut (skema lolos), berbalik arah (reversal), atau keluar/ditahan
     * sesuai [AutoConfig.recheckFailAction].
     */
    private suspend fun startRecheck(kind: String, roi: Double, now: Long) {
        val dirText = if (direction > 0) "LONG" else "SHORT"
        val hitLevel = if (kind == "TP") tpLevel else slLevel

        // level terakhir turun (hard SL) = keluar total, tidak ada cek lanjut
        if (kind == "SL" && hitLevel >= hardSlRoi) {
            log("patokan -${hitLevel.toInt()}% ($dirText, ROI ${roi.toInt()}%) — keluar total")
            notify("Batas -${hitLevel.toInt()}% tercapai. Keluar total.")
            busy(true)
            try {
                closeIfOpen(api)
            } finally {
                busy(false)
            }
            neutralizeAllModels("batas -${hitLevel.toInt()}%")
            phase = Phase.IDLE
            clearState()
            saveState()
            return
        }

        phase = Phase.RECHECK
        recheckKind = kind
        recheckStart = now
        recheckUntil = now + recheckMs
        recheckSigs.clear()
        recheckSnapshotAt = lastSnapshotAt

        // siapkan tangga berikutnya (dipakai hanya kalau lolos cek)
        if (kind == "TP") {
            nextTp = nextTpAfter(hitLevel)
            nextLeverage = nextLeverageAfter(leverage)
            nextSl = cfg.entrySl
        } else {
            nextLeverage = leverage
            nextTp = tpLevel
            nextSl = nextSlAfter(hitLevel)
        }

        log(
            "patokan ${if (kind == "TP") "+" else "-"}${hitLevel.toInt()}% tersentuh ($dirText, ROI ${roi.toInt()}%) " +
                "— posisi DIAMANKAN, 6 model dinetralkan, cek ${cfg.recheckMin} menit",
        )
        notify(
            "Patokan ${if (kind == "TP") "+" else "-"}${hitLevel.toInt()}% kena. " +
                "Posisi tetap jalan, cek model ${cfg.recheckMin} menit.",
        )
        neutralizeAllModels("patokan ${if (kind == "TP") "+" else "-"}${hitLevel.toInt()}%")
        saveState()
    }

    /**
     * Level TP berikutnya dari [AutoConfig.tpLadder]; kalau tangga habis, naik
     * [AutoConfig.tpStep] terus.
     */
    private fun nextTpAfter(current: Double): Double =
        cfg.tpLadder.firstOrNull { it > current } ?: (current + cfg.tpStep)

    /**
     * Leverage berikutnya dari [AutoConfig.leverageLadder]; kalau tangga habis, naik
     * [AutoConfig.leverageStep] sampai [AutoConfig.maxLeverage].
     */
    private fun nextLeverageAfter(current: Int): Int =
        cfg.leverageLadder.firstOrNull { it > current }
            ?: min(current + cfg.leverageStep, cfg.maxLeverage)

    /**
     * Level SL berikutnya dari [AutoConfig.slLadder]; kalau sudah di level terbesar
     * (= SL keras), tetap di situ karena −hardSl = keluar total tanpa cek.
     */
    private fun nextSlAfter(current: Double): Double =
        cfg.slLadder.firstOrNull { it > current } ?: cfg.hardSlRoi

    /** Posisi hilang tanpa lewat engine (SL keras exchange / manual / likuidasi). */
    private fun onPositionClosedExternally(now: Long) {
        if (slLevel >= hardSlRoi) {
            log("posisi tertutup di SL keras -${hardSlRoi.toInt()}% — keluar total, semua model dinetralkan")
        } else {
            log("posisi tertutup di luar patokan (SL keras/manual) — semua model dinetralkan, mulai lagi")
        }
        neutralizeAllModels("posisi tertutup di luar patokan")
        phase = Phase.IDLE
        clearState()
        saveState()
    }

    private suspend fun adopt(pos: BinancePosition) {
        direction = if (pos.isLong) 1 else -1
        leverage = pos.leverage.coerceAtLeast(1)
        entryPrice = pos.entryPrice
        if (tpLevel <= 0) tpLevel = cfg.entryTp
        if (slLevel <= 0) slLevel = cfg.entrySl
        val mark = pos.markPrice.takeIf { it > 0 } ?: lastMark
        lastRoi = if (entryPrice > 0 && mark > 0) direction * (mark / entryPrice - 1) * leverage * 100 else 0.0
        phase = Phase.HOLDING
        recheckSigs.clear()
        log("posisi lama diadopsi ${if (direction > 0) "LONG" else "SHORT"} @ ${priceText(entryPrice)} · ROI ${lastRoi.toInt()}%")
        val client = api
        if (client != null && entryPrice > 0) {
            try {
                // Auto hanya memakai SL keras -40%; order manual lama diganti.
                client.cancelAll(SYMBOL)
                placeHardStop(client, direction, entryPrice, leverage)
                log("SL keras -${hardSlRoi.toInt()}% dipasang untuk posisi adopsi")
            } catch (e: Exception) {
                log("gagal memasang SL keras pada posisi adopsi: ${e.message}")
            }
        }
        saveState()
    }

    // --------------------------------------------------------------- manual

    fun manualOpen(
        dir: Int,
        marginUsd: Double,
        lev: Int,
        tpPct: Double,
        slPct: Double,
        done: (String?) -> Unit,
    ) {
        if (BinanceStore.autoEnabled(ctx)) {
            done("Matikan Auto dulu")
            return
        }
        if (BinanceState.position != null) {
            done("Masih ada posisi terbuka")
            return
        }
        if (marginUsd <= 0 || lev <= 0) {
            done("Margin/leverage tidak valid")
            return
        }
        scope.launch {
            busy(true)
            var client: BinanceApi? = null
            try {
                val creds = BinanceStore.loadCredentials(ctx)
                    ?: throw IllegalStateException("API key belum diisi")
                client = ensureApi(creds) ?: throw IllegalStateException("tidak tersambung")
                val account = client.account()
                lastAccount = account
                publishAccount(account)
                if (account.positions.any { it.symbol == SYMBOL }) {
                    throw IllegalStateException("Masih ada posisi terbuka")
                }
                val livePrice = try {
                    client.markPrice(SYMBOL)
                } catch (_: Exception) {
                    0.0
                }
                val price = livePrice.takeIf { it > 0 }
                    ?: throw IllegalStateException("harga tidak tersedia")
                client.setLeverage(SYMBOL, lev)
                val rules = client.rules(SYMBOL)
                val qtyText = client.formatQty(marginUsd * lev / price, rules)
                val qty = qtyText.toDoubleOrNull() ?: 0.0
                if (qty <= 0 || qty * price < rules.minNotional) {
                    throw IllegalStateException("margin di bawah minimum")
                }
                val side = if (dir > 0) "BUY" else "SELL"
                val posSide = if (client.hedgeMode) (if (dir > 0) "LONG" else "SHORT") else null
                client.marketOrder(SYMBOL, side, qtyText, clientId("m"), positionSide = posSide)
                val pos = waitForPosition(System.currentTimeMillis() + 8_000)
                    ?: throw IllegalStateException("posisi tidak muncul")
                val entry = pos.entryPrice.takeIf { it > 0 } ?: price
                placeManualProtection(client, dir, entry, lev, tpPct, slPct)
                log("manual ${if (dir > 0) "LONG" else "SHORT"} ${lev}x · margin ${money(marginUsd)}")
                notify("Manual ${if (dir > 0) "LONG" else "SHORT"} $lev x")
                done(null)
            } catch (e: Exception) {
                try {
                    client?.cancelAll(SYMBOL)
                } catch (_: Exception) {
                }
                closeIfOpen(client)
                done(e.message ?: "gagal order")
            } finally {
                busy(false)
            }
        }
    }

    /** Trading manual tetap memakai TP/SL order di exchange sesuai isian pengguna. */
    private suspend fun placeManualProtection(
        client: BinanceApi,
        dir: Int,
        entry: Double,
        lev: Int,
        tpPct: Double,
        slPct: Double,
    ) {
        val rules = client.rules(SYMBOL)
        val posSide = if (client.hedgeMode) (if (dir > 0) "LONG" else "SHORT") else null
        val exitSide = if (dir > 0) "SELL" else "BUY"
        if (tpPct > 0) {
            val tp = if (dir > 0) entry * (1 + tpPct / 100.0 / lev) else entry * (1 - tpPct / 100.0 / lev)
            client.closePositionOrder(
                SYMBOL, exitSide, "TAKE_PROFIT_MARKET",
                client.formatPrice(tp, rules), clientId("tp"), posSide,
            )
        }
        if (slPct > 0) {
            val sl = if (dir > 0) entry * (1 - slPct / 100.0 / lev) else entry * (1 + slPct / 100.0 / lev)
            client.closePositionOrder(
                SYMBOL, exitSide, "STOP_MARKET",
                client.formatPrice(sl, rules), clientId("sl"), posSide,
            )
        }
    }

    fun manualClose(done: (String?) -> Unit) {
        scope.launch {
            busy(true)
            var client: BinanceApi? = null
            try {
                val creds = BinanceStore.loadCredentials(ctx)
                    ?: throw IllegalStateException("API key belum diisi")
                client = ensureApi(creds) ?: throw IllegalStateException("tidak tersambung")
                val account = client.account()
                lastAccount = account
                publishAccount(account)
                val pos = account.positions.firstOrNull { it.symbol == SYMBOL }
                    ?: throw IllegalStateException("tidak ada posisi")
                client.cancelAll(SYMBOL)
                val side = if (pos.isLong) "SELL" else "BUY"
                val posSide = if (client.hedgeMode) (if (pos.isLong) "LONG" else "SHORT") else null
                val qty = client.formatQty(pos.absAmount, client.rules(SYMBOL))
                client.marketOrder(
                    SYMBOL, side, qty, clientId("c"),
                    reduceOnly = !client.hedgeMode, positionSide = posSide,
                )
                delay(1_000)
                val after = client.account()
                lastAccount = after
                publishAccount(after)
                phase = Phase.IDLE
                clearState()
                log("posisi ditutup manual")
                notify("Posisi ditutup manual")
                done(null)
            } catch (e: Exception) {
                done(e.message ?: "gagal tutup posisi")
            } finally {
                busy(false)
            }
        }
    }

    private suspend fun closeIfOpen(client: BinanceApi?) {
        if (client == null) return
        try {
            val acc = client.account()
            val pos = acc.positions.firstOrNull { it.symbol == SYMBOL } ?: return
            client.cancelAll(SYMBOL)
            val side = if (pos.isLong) "SELL" else "BUY"
            val posSide = if (client.hedgeMode) (if (pos.isLong) "LONG" else "SHORT") else null
            val qty = client.formatQty(pos.absAmount, client.rules(SYMBOL))
            client.marketOrder(
                SYMBOL, side, qty, clientId("x"),
                reduceOnly = !client.hedgeMode, positionSide = posSide,
            )
            lastAccount = client.account()
            lastAccount?.let { publishAccount(it) }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- state

    private fun clearState() {
        batch.clear()
        latest.clear()
        current.clear()
        lastSeen.clear()
        recheckSigs.clear()
        batchStart = 0
        batchCarryAt = 0
        recheckStart = 0
        recheckUntil = 0
        cooldownUntil = 0
        recheckKind = ""
        entryPrice = 0.0
        hardSlPrice = 0.0
        lastRoi = 0.0
        direction = 0
        leverage = cfg.entryLeverage
        tpLevel = cfg.entryTp
        slLevel = cfg.entrySl
        nextLeverage = cfg.entryLeverage
        nextTp = cfg.entryTp
        nextSl = cfg.entrySl
        seeded = true
    }

    private fun publishAccount(acc: BinanceAccount) {
        val pos = acc.positions.firstOrNull { it.symbol == SYMBOL }
        ui {
            BinanceState.connected = true
            BinanceState.lastError = null
            BinanceState.lastUpdate = System.currentTimeMillis()
            BinanceState.walletBalance = acc.walletBalance
            BinanceState.availableBalance = acc.availableBalance
            BinanceState.marginBalance = acc.marginBalance
            BinanceState.unrealizedPnl = acc.unrealizedPnl
            BinanceState.position = pos
        }
    }

    private fun publishAuto() {
        val total = modelNames.size.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        var responded = 0
        var aligned = 0
        val detail = when (phase) {
            Phase.BATCH -> {
                val counts = countSides(batch)
                responded = batch.size
                aligned = max(counts.longs, counts.shorts)
                "Sinyal $responded/$total · sisa ${mmss(batchStart + entryWindowMs - now)}"
            }
            Phase.HOLDING -> {
                responded = modelNames.count { (current[it] ?: 0) != 0 }
                aligned = modelNames.count { current[it] == direction }
                "${if (direction > 0) "LONG" else "SHORT"} ${leverage}x · ROI ${lastRoi.toInt()}% · target +${tpLevel.toInt()}% / -${slLevel.toInt()}%"
            }
            Phase.RECHECK -> {
                responded = recheckSigs.size
                aligned = recheckSigs.values.count { it.side == direction }
                val reversed = reversalCount(recheckSigs, direction)
                val hit = if (recheckKind == "TP") "+${tpLevel.toInt()}%" else "-${slLevel.toInt()}%"
                "Posisi MASIH JALAN ${leverage}x · ROI ${lastRoi.toInt()}% · model $responded/$total · " +
                    "balik $reversed · cek $hit sisa ${mmss(recheckUntil - now)}"
            }
            Phase.IDLE -> if (cooldownUntil > now) {
                "Jeda entry ${mmss(cooldownUntil - now)} · window ${cfg.entryWindowMin} menit"
            } else {
                "Menunggu pola ${cfg.minAgree}/$total · window ${cfg.entryWindowMin} menit"
            }
        }
        ui {
            BinanceState.autoPhase = phase.name
            BinanceState.autoDetail = detail
            BinanceState.modelsResponded = responded
            BinanceState.modelsAligned = aligned
            BinanceState.modelCount = total
            BinanceState.leverage = leverage
            BinanceState.tpLevel = tpLevel
            BinanceState.slLevel = slLevel
            BinanceState.hardSlRoi = hardSlRoi
            BinanceState.direction = direction
            BinanceState.entryPrice = entryPrice
            BinanceState.roiPct = lastRoi
        }
    }

    // ----------------------------------------------------------- persistence

    private fun saveState() {
        try {
            val o = JSONObject()
            o.put("phase", phase.name)
            o.put("batchStart", batchStart)
            o.put("batchCarryAt", batchCarryAt)
            o.put("seeded", seeded)
            o.put("direction", direction)
            o.put("leverage", leverage)
            o.put("tpLevel", tpLevel)
            o.put("slLevel", slLevel)
            o.put("nextLeverage", nextLeverage)
            o.put("nextTp", nextTp)
            o.put("nextSl", nextSl)
            o.put("recheckStart", recheckStart)
            o.put("recheckUntil", recheckUntil)
            o.put("cooldownUntil", cooldownUntil)
            o.put("recheckKind", recheckKind)
            o.put("entryPrice", entryPrice)
            o.put("hardSlPrice", hardSlPrice)
            o.put("lastRoi", lastRoi)
            o.put("batch", sigMapToJson(batch))
            o.put("latest", sigMapToJson(latest))
            o.put("recheckSigs", sigMapToJson(recheckSigs))
            val seen = JSONObject()
            for ((k, v) in lastSeen) seen.put(k, v)
            o.put("lastSeen", seen)
            BinanceStore.setAutoState(ctx, o.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadState() {
        if (stateLoaded) return
        stateLoaded = true
        val raw = BinanceStore.autoState(ctx) ?: return
        try {
            val o = JSONObject(raw)
            val savedPhase = o.optString("phase", "IDLE")
            // Fase lama (NEUTRAL_WAIT / CORRECTION_WAIT / HOLD_ENTRY) sudah tidak ada:
            // kembali ke IDLE supaya window 30 menit baru dimulai bersih.
            phase = runCatching { Phase.valueOf(savedPhase) }.getOrDefault(Phase.IDLE)
            batchStart = o.optLong("batchStart", 0)
            batchCarryAt = o.optLong("batchCarryAt", 0)
            seeded = o.optBoolean("seeded", false)
            direction = o.optInt("direction", 0)
            leverage = o.optInt("leverage", cfg.entryLeverage)
            tpLevel = o.optDouble("tpLevel", cfg.entryTp)
            slLevel = o.optDouble("slLevel", cfg.entrySl)
            nextLeverage = o.optInt("nextLeverage", cfg.entryLeverage)
            nextTp = o.optDouble("nextTp", cfg.entryTp)
            nextSl = o.optDouble("nextSl", cfg.entrySl)
            recheckStart = o.optLong("recheckStart", 0)
            recheckUntil = o.optLong("recheckUntil", 0)
            cooldownUntil = o.optLong("cooldownUntil", 0)
            recheckKind = o.optString("recheckKind", "")
            entryPrice = o.optDouble("entryPrice", 0.0)
            hardSlPrice = o.optDouble("hardSlPrice", 0.0)
            lastRoi = o.optDouble("lastRoi", 0.0)
            readSigMap(o.optJSONObject("batch"), batch)
            readSigMap(o.optJSONObject("latest"), latest)
            readSigMap(o.optJSONObject("recheckSigs"), recheckSigs)
            val seen = o.optJSONObject("lastSeen") ?: JSONObject()
            for (k in seen.keys()) lastSeen[k] = seen.optLong(k, 0)
        } catch (_: Exception) {
        }
    }

    private fun sigMapToJson(map: Map<String, Sig>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONObject().put("side", v.side).put("ts", v.ts))
        return o
    }

    private fun readSigMap(o: JSONObject?, target: MutableMap<String, Sig>) {
        if (o == null) return
        for (k in o.keys()) {
            val v = o.optJSONObject(k) ?: continue
            target[k] = Sig(v.optInt("side", 0), v.optLong("ts", 0))
        }
    }

    // --------------------------------------------------------------- helpers

    private fun clientId(prefix: String): String {
        val tail = System.currentTimeMillis().toString().takeLast(9)
        return "sim$prefix$tail${(100..999).random()}"
    }

    private fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun priceText(v: Double): String = String.format(Locale.US, "%.4f", v)

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }.format(Date())
        val line = "$t $msg"
        ui { BinanceState.autoLog = (listOf(line) + BinanceState.autoLog).take(30) }
    }

    private fun notify(text: String) {
        try {
            Notifier.fireAuto(ctx, text)
        } catch (_: Exception) {
        }
    }

    private fun fail(msg: String) {
        ui {
            BinanceState.connected = false
            BinanceState.lastError = msg
            BinanceState.lastUpdate = System.currentTimeMillis()
        }
    }

    private fun busy(value: Boolean) {
        ui { BinanceState.busy = value }
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }
}
