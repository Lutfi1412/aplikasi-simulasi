package com.simulasi.trading.binance

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * Pembagian tugas (sesuai aturan main):
 *  - Patokan +22% / +34% / +50% / +15% berikutnya dan -22% / -31% adalah **level di aplikasi**.
 *    Saat ROI menyentuhnya, engine menutup posisi lalu mengecek 25 menit apakah model masih
 *    selaras; kalau ya, entry lagi di leverage tangga berikutnya, kalau tidak, netral.
 *  - Di exchange hanya dipasang **SL keras -40% ROI** (closePosition) sebagai pengaman
 *    terakhir kalau aplikasi/HP mati. Tidak ada order TP di exchange.
 *
 * Sumber sinyal: /api/snapshot (6 model, sinyal per menit + roiPct untuk aturan gap).
 * Kredensial: BinanceStore (Android Keystore). Tidak ada kunci yang dikirim ke VPS.
 */
class AutoEngine(private val ctx: Context) {

    companion object {
        @Volatile var instance: AutoEngine? = null

        private const val SYMBOL = "SOLUSDT"
        private const val ENTRY_WINDOW_MS = 23L * 60_000
        private const val RECHECK_MS = 25L * 60_000
        private const val NEUTRAL_WAIT_MS = 30L * 60_000
        private const val RETENTION_MS = 5L * 60_000
        private const val HOLD_MS = 30L * 60_000
        private const val GAP_PCT = 22.0
        private const val BASE_LEVERAGE = 30
        private const val MAX_LEVERAGE = 40
        private const val MIN_AGREE = 5

        /** SL keras di exchange: -40% ROI (sama dengan batas keluar terakhir). */
        private const val HARD_SL_ROI = 40.0
        private const val FIRST_TP = 22.0
        private const val FIRST_SL = 22.0

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

    private enum class Phase { IDLE, BATCH, HOLD_ENTRY, NEUTRAL_WAIT, HOLDING, RECHECK }
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
    private var seeded = false
    private var modelNames: List<String> = DEFAULT_MODELS

    private var direction = 0
    private var leverage = BASE_LEVERAGE
    /** level patokan naik berikutnya (ROI %) dan turun berikutnya (ROI %) */
    private var tpLevel = FIRST_TP
    private var slLevel = FIRST_SL
    private var nextLeverage = BASE_LEVERAGE
    private var nextTp = FIRST_TP
    private var nextSl = FIRST_SL
    private var holdLeverage = BASE_LEVERAGE
    private var holdTp = FIRST_TP
    private var holdSl = FIRST_SL

    private var recheckStart = 0L
    private var recheckUntil = 0L
    private var holdUntil = 0L
    private var neutralUntil = 0L

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

    fun start() {
        if (job?.isActive == true) return
        instance = this
        job = scope.launch {
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
            BinanceState.autoDetail = "Menunggu pola 5/6"
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
        if (phase == Phase.NEUTRAL_WAIT) return

        for (m in snap.models) {
            current[m.name] = m.side
            if (m.side == 0) continue
            val sig = Sig(m.side, now)
            latest[m.name] = sig
            lastSeen[m.name] = now
            if (phase == Phase.IDLE || phase == Phase.BATCH) {
                if (batch.isEmpty()) batchStart = now
                val old = batch[m.name]
                if (old == null || old.side != m.side) batch[m.name] = sig
                if (phase == Phase.IDLE) phase = Phase.BATCH
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
            Phase.HOLD_ENTRY -> if (pos != null) adopt(pos) else evaluateHold(now, snap)
            Phase.NEUTRAL_WAIT -> {
                if (pos != null) adopt(pos) else if (now >= neutralUntil) resetNeutral()
            }
            Phase.IDLE -> if (pos != null) adopt(pos)
        }
        publishAuto()
    }

    private suspend fun evaluateBatch(now: Long, snap: SnapshotData) {
        val total = modelNames.size.coerceAtLeast(1)
        val deadline = batchStart + ENTRY_WINDOW_MS
        if (now < deadline) return

        if (batch.size < total) {
            val responded = batch.size
            val cutoff = deadline - RETENTION_MS
            val keep = batch.filterValues { it.ts >= cutoff }
            batch.clear()
            batch.putAll(keep)
            if (keep.isNotEmpty()) {
                batchStart = keep.values.minOf { it.ts }
                log("entry gagal: $responded/$total merespon — ${keep.size} sinyal terakhir dibawa ke batch berikutnya")
                saveState()
            } else {
                neutral("entry gagal: hanya $responded/$total model merespon — netral 30 menit", now, clearNow = true)
            }
            return
        }

        val longs = batch.values.count { it.side > 0 }
        val shorts = batch.size - longs
        val majority = if (longs >= shorts) 1 else -1
        val agree = max(longs, shorts)
        if (agree < MIN_AGREE) {
            neutral("sinyal sepakat hanya $agree/$total — netral 30 menit", now, clearNow = false)
            return
        }
        tryEnter(majority, now, snap, "entry $agree/$total")
    }

    /**
     * Setelah gagal entry karena gap ROI: tunggu 30 menit, lalu nilai ulang memakai
     * stance TERBARU tiap model (`current`, bukan batch yang dibekukan). Kalau sudah
     * lengkap, ≥5 selaras, dan tidak ada gap → entry. Kalau belum → netralkan semuanya
     * dan mulai kumpulkan pola dari awal (persis siklus yang diminta).
     */
    private suspend fun evaluateHold(now: Long, snap: SnapshotData) {
        if (now < holdUntil) return
        val total = modelNames.size.coerceAtLeast(1)
        val responded = modelNames.count { (current[it] ?: 0) != 0 }
        val longs = modelNames.count { (current[it] ?: 0) > 0 }
        val shorts = modelNames.count { (current[it] ?: 0) < 0 }
        val majority = if (longs >= shorts) 1 else -1
        val agree = max(longs, shorts)
        if (responded >= total && agree >= MIN_AGREE && !roiGapBlocks(majority, snap)) {
            doEntry(majority, holdLeverage, holdTp, holdSl, now, snap, "entry setelah tahan 30 menit")
            return
        }
        batch.clear()
        latest.clear()
        current.clear()
        batchStart = 0
        holdUntil = 0
        phase = Phase.NEUTRAL_WAIT
        neutralUntil = now + NEUTRAL_WAIT_MS
        log("masa tahan habis (${responded}/$total respon, $agree selaras) — semua model dinetralkan, ulangi pola")
        neutralizeAllModels("masa tahan habis")
        saveState()
        ui {
            BinanceState.autoPhase = "NEUTRAL_WAIT"
            BinanceState.autoDetail = "Netral · pola baru ${mmss(neutralUntil - now)}"
        }
    }

    /**
     * Dipanggil tiap loop selama fase RECHECK (posisi masih terbuka & masih trading).
     *
     * - SL keras -40% tetap dipasang/dipelihara di exchange.
     * - Patokan berikutnya BELUM berlaku sampai 25 menit selesai (posisi dibiarkan
     *   berjalan mencari keuntungan lebih).
     * - Setelah 25 menit: kalau keenam model merespon, ≥5 selaras arah posisi, dan tidak
     *   ada gap ROI ≥22% (model teratas tidak berbalik arah) → LANJUT: naikkan leverage
     *   dan pasang patokan berikutnya. Kalau tidak → keluar sekarang.
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

        if (now < recheckUntil) return

        val total = modelNames.size.coerceAtLeast(1)
        val responded = modelNames.count { (current[it] ?: 0) != 0 }
        if (responded < total) {
            exitAfterRecheck("cek 25 menit gagal: hanya $responded/$total merespon", now)
            return
        }
        val aligned = modelNames.count { current[it] == direction }
        if (aligned < MIN_AGREE) {
            exitAfterRecheck("cek 25 menit: hanya $aligned/$total selaras arah posisi", now)
            return
        }
        if (roiGapBlocks(direction, snap)) {
            exitAfterRecheck("cek 25 menit: model ROI tertinggi berbalik arah (gap ≥ 22%)", now)
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
        val dirText = if (direction > 0) "LONG" else "SHORT"
        log(
            "$dirText lanjut (${aligned}/${total} selaras) · leverage ${leverage}x · " +
                "patokan berikut +${tpLevel.toInt()}% / -${slLevel.toInt()}%",
        )
        notify("$dirText lanjut ${leverage}x. Patokan +${tpLevel.toInt()}% / -${slLevel.toInt()}%.")
        saveState()
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

    /** Pastikan SL keras -40% masih ada; pasang ulang bila hilang. */
    private fun keepHardStop(client: BinanceApi, pos: BinancePosition) {
        scope.launch {
            try {
                val orders = client.openOrders(SYMBOL)
                if (orders.none { it.optString("type") == "STOP_MARKET" }) {
                    placeHardStop(client, direction, entryPrice, leverage.coerceAtLeast(1))
                    log("SL keras -${HARD_SL_ROI.toInt()}% dipasang ulang")
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun tryEnter(dir: Int, now: Long, snap: SnapshotData, reason: String) {
        if (roiGapBlocks(dir, snap)) {
            phase = Phase.HOLD_ENTRY
            holdUntil = now + HOLD_MS
            holdLeverage = nextLeverage
            holdTp = nextTp
            holdSl = nextSl
            log("tahan entry: model ROI tertinggi beda arah, gap ≥ 22% — tunggu 30 menit")
            saveState()
            return
        }
        doEntry(dir, nextLeverage, nextTp, nextSl, now, snap, reason)
    }

    /**
     * Aturan NOTE: kalau model dengan ROI tertinggi berbeda arah dari mayoritas dan
     * jarak ROI-nya ke peringkat kedua ≥ 22 poin, tahan entry (jangan lawan model itu).
     */
    private fun roiGapBlocks(dir: Int, snap: SnapshotData): Boolean {
        if (snap.bots.size < 2) return false
        val sorted = snap.bots.sortedByDescending { it.roiPct }
        val top = sorted[0]
        val second = sorted[1]
        if (top.roiPct - second.roiPct < GAP_PCT) return false
        val topSide = current[top.model]
            ?: batch[top.model]?.side
            ?: latest[top.model]?.side
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
    ) {
        if (BinanceState.busy) return
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
            seeded = true
            lastProtectCheck = now

            log(
                "posisi ${if (dir > 0) "LONG" else "SHORT"} @ ${priceText(entry)} · " +
                    "patokan app +${tpPct.toInt()}% / -${slPct.toInt()}% · SL keras -${HARD_SL_ROI.toInt()}%",
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
     * Hanya SL keras di exchange (-40% ROI dari entry) — pengaman kalau aplikasi mati.
     * Level +22/+34/+50 dan -22/-31 dikelola engine (bukan order).
     */
    private suspend fun placeHardStop(client: BinanceApi, dir: Int, entry: Double, lev: Int) {
        val rules = client.rules(SYMBOL)
        val posSide = if (client.hedgeMode) (if (dir > 0) "LONG" else "SHORT") else null
        val exitSide = if (dir > 0) "SELL" else "BUY"
        val sl = if (dir > 0) {
            entry * (1 - HARD_SL_ROI / 100.0 / lev)
        } else {
            entry * (1 + HARD_SL_ROI / 100.0 / lev)
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
     * Patokan tersentuh → MULAI cek 25 menit. Posisi TIDAK ditutup.
     * Keenam model dinetralkan supaya bisa mencari pola baru; 25 menit kemudian kita
     * lihat apakah mereka masih searah posisi (lanjut) atau berbalik arah (keluar).
     */
    private suspend fun startRecheck(kind: String, roi: Double, now: Long) {
        val dirText = if (direction > 0) "LONG" else "SHORT"
        val hitLevel = if (kind == "TP") tpLevel else slLevel

        // level terakhir turun (-40%) = keluar total, tidak ada cek lanjut
        if (kind == "SL" && hitLevel >= HARD_SL_ROI) {
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
        recheckUntil = now + RECHECK_MS

        // siapkan tangga berikutnya (dipakai hanya kalau lolos cek 25 menit)
        if (kind == "TP") {
            nextTp = nextTpAfter(hitLevel)
            nextLeverage = min(leverage + 5, MAX_LEVERAGE)
            nextSl = FIRST_SL
        } else {
            nextLeverage = leverage
            nextTp = tpLevel
            nextSl = if (hitLevel < 31.0) 31.0 else HARD_SL_ROI
        }

        log(
            "patokan ${if (kind == "TP") "+" else "-"}${hitLevel.toInt()}% tersentuh ($dirText, ROI ${roi.toInt()}%) " +
                "— posisi DIAMANKAN, 6 model dinetralkan, cek 25 menit",
        )
        notify("Patokan ${if (kind == "TP") "+" else "-"}${hitLevel.toInt()}% kena. Posisi tetap jalan, cek model 25 menit.")
        neutralizeAllModels("patokan ${if (kind == "TP") "+" else "-"}${hitLevel.toInt()}%")
        saveState()
    }

    private fun nextTpAfter(current: Double): Double = when {
        current < 34.0 -> 34.0
        current < 50.0 -> 50.0
        else -> current + 15.0
    }

    /** Posisi hilang tanpa lewat engine (SL keras exchange / manual / likuidasi). */
    private fun onPositionClosedExternally(now: Long) {
        if (slLevel >= HARD_SL_ROI) {
            log("posisi tertutup di SL keras -${HARD_SL_ROI.toInt()}% — keluar total, semua model dinetralkan")
        } else {
            log("posisi tertutup di luar patokan (SL keras/manual) — semua model dinetralkan, mulai lagi")
        }
        neutralizeAllModels("posisi tertutup di luar patokan")
        phase = Phase.IDLE
        clearState()
        saveState()
    }

    private fun adopt(pos: BinancePosition) {
        direction = if (pos.isLong) 1 else -1
        leverage = pos.leverage.coerceAtLeast(1)
        entryPrice = pos.entryPrice
        if (tpLevel <= 0) tpLevel = FIRST_TP
        if (slLevel <= 0) slLevel = FIRST_SL
        val mark = pos.markPrice.takeIf { it > 0 } ?: lastMark
        lastRoi = if (entryPrice > 0 && mark > 0) direction * (mark / entryPrice - 1) * leverage * 100 else 0.0
        phase = Phase.HOLDING
        log("posisi lama diadopsi ${if (direction > 0) "LONG" else "SHORT"} @ ${priceText(entryPrice)} · ROI ${lastRoi.toInt()}%")
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

    private fun neutral(msg: String, now: Long, clearNow: Boolean) {
        log(msg)
        neutralizeAllModels("netral")
        phase = Phase.NEUTRAL_WAIT
        neutralUntil = now + NEUTRAL_WAIT_MS
        if (clearNow) {
            batch.clear()
            latest.clear()
        }
        saveState()
    }

    private fun resetNeutral() {
        phase = Phase.IDLE
        batch.clear()
        latest.clear()
        current.clear()
        lastSeen.clear()
        batchStart = 0
        holdUntil = 0
        neutralUntil = 0
        seeded = true
        log("netral — menunggu pola baru")
        saveState()
    }

    private fun clearState() {
        batch.clear()
        latest.clear()
        current.clear()
        lastSeen.clear()
        batchStart = 0
        holdUntil = 0
        neutralUntil = 0
        recheckStart = 0
        recheckUntil = 0
        recheckKind = ""
        entryPrice = 0.0
        hardSlPrice = 0.0
        lastRoi = 0.0
        direction = 0
        leverage = BASE_LEVERAGE
        tpLevel = FIRST_TP
        slLevel = FIRST_SL
        nextLeverage = BASE_LEVERAGE
        nextTp = FIRST_TP
        nextSl = FIRST_SL
        holdLeverage = BASE_LEVERAGE
        holdTp = FIRST_TP
        holdSl = FIRST_SL
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
                responded = batch.size
                val longs = batch.values.count { it.side > 0 }
                aligned = max(longs, batch.size - longs)
                "Sinyal $responded/$total · sisa ${mmss(batchStart + ENTRY_WINDOW_MS - now)}"
            }
            Phase.HOLD_ENTRY -> {
                responded = batch.size
                val longs = batch.values.count { it.side > 0 }
                aligned = max(longs, batch.size - longs)
                "Tahan entry (gap ROI) · netral dalam ${mmss(holdUntil - now)}"
            }
            Phase.NEUTRAL_WAIT -> "Netral · pola baru ${mmss(neutralUntil - now)}"
            Phase.HOLDING -> {
                responded = modelNames.count { (current[it] ?: 0) != 0 }
                aligned = modelNames.count { current[it] == direction }
                "${if (direction > 0) "LONG" else "SHORT"} ${leverage}x · ROI ${lastRoi.toInt()}% · target +${tpLevel.toInt()}% / -${slLevel.toInt()}%"
            }
            Phase.RECHECK -> {
                responded = modelNames.count { (current[it] ?: 0) != 0 }
                aligned = modelNames.count { current[it] == direction }
                val hit = if (recheckKind == "TP") "+${tpLevel.toInt()}%" else "-${slLevel.toInt()}%"
                "Posisi MASIH JALAN ${leverage}x · ROI ${lastRoi.toInt()}% · cek $hit sisa ${mmss(recheckUntil - now)}"
            }
            Phase.IDLE -> "Menunggu pola 5/6 · $total model"
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
            BinanceState.hardSlRoi = HARD_SL_ROI
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
            o.put("seeded", seeded)
            o.put("direction", direction)
            o.put("leverage", leverage)
            o.put("tpLevel", tpLevel)
            o.put("slLevel", slLevel)
            o.put("nextLeverage", nextLeverage)
            o.put("nextTp", nextTp)
            o.put("nextSl", nextSl)
            o.put("holdLeverage", holdLeverage)
            o.put("holdTp", holdTp)
            o.put("holdSl", holdSl)
            o.put("recheckStart", recheckStart)
            o.put("recheckUntil", recheckUntil)
            o.put("recheckKind", recheckKind)
            o.put("holdUntil", holdUntil)
            o.put("neutralUntil", neutralUntil)
            o.put("entryPrice", entryPrice)
            o.put("hardSlPrice", hardSlPrice)
            o.put("lastRoi", lastRoi)
            o.put("batch", sigMapToJson(batch))
            o.put("latest", sigMapToJson(latest))
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
            phase = runCatching { Phase.valueOf(o.optString("phase", "IDLE")) }
                .getOrDefault(Phase.IDLE)
            batchStart = o.optLong("batchStart", 0)
            seeded = o.optBoolean("seeded", false)
            direction = o.optInt("direction", 0)
            leverage = o.optInt("leverage", BASE_LEVERAGE)
            tpLevel = o.optDouble("tpLevel", FIRST_TP)
            slLevel = o.optDouble("slLevel", FIRST_SL)
            nextLeverage = o.optInt("nextLeverage", BASE_LEVERAGE)
            nextTp = o.optDouble("nextTp", FIRST_TP)
            nextSl = o.optDouble("nextSl", FIRST_SL)
            holdLeverage = o.optInt("holdLeverage", nextLeverage)
            holdTp = o.optDouble("holdTp", nextTp)
            holdSl = o.optDouble("holdSl", nextSl)
            recheckStart = o.optLong("recheckStart", 0)
            recheckUntil = o.optLong("recheckUntil", 0)
            recheckKind = o.optString("recheckKind", "")
            holdUntil = o.optLong("holdUntil", 0)
            neutralUntil = o.optLong("neutralUntil", 0)
            entryPrice = o.optDouble("entryPrice", 0.0)
            hardSlPrice = o.optDouble("hardSlPrice", 0.0)
            lastRoi = o.optDouble("lastRoi", 0.0)
            readSigMap(o.optJSONObject("batch"), batch)
            readSigMap(o.optJSONObject("latest"), latest)
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
