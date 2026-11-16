package com.simulasi.trading.binance

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.simulasi.trading.data.AutoCommon
import com.simulasi.trading.data.BinanceCredentials
import com.simulasi.trading.data.BinanceState
import com.simulasi.trading.data.BinanceStore
import com.simulasi.trading.data.Prefs
import com.simulasi.trading.data.SchemeRule
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
 * Revisi #31:
 *  - "Respon" = model yang sinyalnya **non-zero SEKARANG** (sama dengan Home), bukan model
 *    yang pernah bersinyal lalu kembali netral. Panel menampilkan angka aktif + terkumpul.
 *  - Skema yang cocok langsung **ACC** (tidak menunggu 30 menit); window 30 menit hanya batas
 *    tunggu, dan setelah habis tetap **dibandingkan** sebelum aksi gagal dijalankan.
 *  - Netral Home hanya di tiga momen ([poin di bawah]): Auto ON, window/skema gagal, dan cek
 *    recheck gagal. Hasil netral **diverifikasi** (diulang) karena server bisa melewati bot
 *    yang harganya belum tersedia.
 *  - Tidak ada skema bawaan: Auto memakai skema buatan pengguna; kalau kosong, Auto diam.
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
        /** Berapa kali netral-all diulang kalau masih ada bot Home yang memegang posisi. */
        private const val NEUTRAL_ATTEMPTS = 5
        private const val NEUTRAL_RETRY_MS = 2_000L

        /** Fee taker Binance Futures (0,05%) — dipakai menyisakan margin untuk fee. */
        private const val TAKER_FEE = 0.0005
        /** Cadangan tambahan untuk selisih mark price vs harga fill. */
        private const val SLIPPAGE_RESERVE = 0.01
        /** Berapa kali order entry diperkecil otomatis kalau Binance menolak margin. */
        private const val ENTRY_SHRINK_TRIES = 3
        /** Kode Binance yang berarti "margin/ukuran kurang" → layak dicoba lebih kecil. */
        private val SHRINKABLE_CODES = setOf(-2019, -2013, -4003, -1013, -2020)
        /** Bentuk IPv4 valid untuk fallback IP publik. */
        private val IPV4 = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")

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
    /**
     * Sinyal yang terkumpul di window entry (akumulasi, stance terakhir per model).
     * Ini yang dipakai menilai skema, karena window memang mengumpulkan jawaban.
     */
    private val batch = LinkedHashMap<String, Sig>()
    /**
     * Model yang sinyalnya **non-zero SEKARANG** — sama dengan yang tampil di Home.
     * Hanya dipakai untuk angka "aktif" di panel, supaya tidak ada lagi angka 3/6
     * padahal Home cuma menunjukkan 2 model bersinyal.
     */
    private val liveSigs = LinkedHashMap<String, Sig>()
    private val latest = LinkedHashMap<String, Sig>()
    /** Sinyal terakhir per model termasuk 0 (dipakai panel saat HOLDING). */
    private val current = LinkedHashMap<String, Int>()
    private val lastSeen = LinkedHashMap<String, Long>()
    /** Sinyal yang terkumpul selama window cek 25 menit (menit 0 = patokan tersentuh). */
    private val recheckSigs = LinkedHashMap<String, Sig>()
    /** Waktu snapshot terakhir saat patokan tersentuh; sinyal yang lebih lama tidak dihitung. */
    private var recheckSnapshotAt = 0L
    private var warnedNoScheme = false
    /**
     * Timestamp sinyal carry saat window digulirkan. Sinyal dengan `ts <= batchCarryAt`
     * adalah warisan window sebelumnya, bukan jawaban window berjalan.
     */
    private var batchCarryAt = 0L
    private var seeded = false
    private var modelNames: List<String> = DEFAULT_MODELS

    private var direction = 0
    private var leverage = AutoCommon.STANDARD.entryLeverage
    /** level patokan naik berikutnya (ROI %) dan turun berikutnya (ROI %) */
    private var tpLevel = AutoCommon.STANDARD.entryTp
    private var slLevel = AutoCommon.STANDARD.entrySl
    private var nextLeverage = AutoCommon.STANDARD.entryLeverage
    private var nextTp = AutoCommon.STANDARD.entryTp
    private var nextSl = AutoCommon.STANDARD.entrySl

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
     * Setelan umum + daftar skema. Dibaca ulang dari [BinanceStore] setiap loop supaya
     * perubahan dari tab Binance langsung berlaku tanpa restart layanan.
     */
    private var cfg = AutoCommon.STANDARD

    private val entryWindowMs: Long get() = cfg.entryWindowMin * 60_000L
    private val carryMs: Long get() = cfg.carryMin * 60_000L
    private val recheckMs: Long get() = cfg.recheckMin * 60_000L
    /** SL terdalam: selalu dipasang sebagai SL keras di exchange. */
    private val hardSlRoi: Double get() = cfg.maxSl

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
        warnedNoScheme = false
        saveState()
        log("Auto ON — semua model dinetralkan, mulai mencari pola baru")
        ui {
            BinanceState.autoPhase = "IDLE"
            BinanceState.autoDetail = "Menunggu sinyal pertama · window ${cfg.entryWindowMin} menit"
            BinanceState.autoBlock = ""
        }
        // Netral sekali di awal Auto ON, lalu diverifikasi (diulang kalau server melewati
        // bot yang harganya belum tersedia). Tidak ada netral berkala setelah ini.
        scope.launch {
            val clean = neutralizeServerBots("Auto ON")
            if (!clean) log("Auto ON: sebagian model Home masih punya posisi (harga feed belum ada) — akan dibersihkan saat window gagal")
        }
    }

    fun onAutoDisabled() {
        lastAutoFlag = false
        phase = Phase.IDLE
        saveState()
        log("Auto OFF — posisi (jika ada) tetap terlindungi SL -40% di exchange")
        ui {
            BinanceState.autoPhase = "OFF"
            BinanceState.autoDetail = "Auto mati · API siap manual"
            BinanceState.autoBlock = ""
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
            "setelan diperbarui: window ${cfg.entryWindowMin}m · lev ${cfg.leverageLadder.joinToString("/")} · " +
                "TP ${cfg.tpLadder.joinToString("/")}${if (cfg.tpExtend) " +${cfg.tpStep.toInt()}" else " (akhir = TP asli)"} · " +
                "SL ${cfg.slLadder.joinToString("/")}${if (cfg.slExtend) " +${cfg.slStep.toInt()}" else " (akhir = SL asli)"} · " +
                "skema entry ${cfg.entrySchemes.size} · recheck ${cfg.recheckSchemes.size}",
        )
        saveState()
        publishAuto()
    }

    /**
     * Netralkan keenam model simulasi di Home (bukan posisi Binance).
     *
     * Dipakai **hanya** di tiga kejadian: Auto ON, window/skema entry gagal, dan cek recheck
     * gagal/berbalik arah. Tidak ada netral berkala — setelah itu Home bebas trading lagi.
     *
     * Server bisa **melewati** bot yang punya posisi kalau harga feed sedang tidak tersedia
     * (`neutralize()` menolak saat `price <= 0`), dan itu penyebab "sesekali ada model yang
     * tidak ikut netral". Karena itu hasilnya diverifikasi dan diulang beberapa kali.
     */
    private fun neutralizeAllModels(reason: String) {
        scope.launch {
            neutralizeServerBots(reason)
        }
    }

    /**
     * POST netral-all lalu **verifikasi** dari `/api/snapshot`: bot yang masih punya
     * posisi/pending berarti server melewatinya (harga belum ada) → ulangi.
     * @return true kalau semua bot sudah bersih.
     */
    private suspend fun neutralizeServerBots(reason: String = "Auto ON"): Boolean {
        val body = "{}".toRequestBody("application/json".toMediaType())
        var remaining = -1
        for (attempt in 1..NEUTRAL_ATTEMPTS) {
            val ok = try {
                val req = Request.Builder()
                    .url("${Prefs.baseUrl(ctx)}/api/bots/neutralize-all")
                    .post(body)
                    .header("cache-control", "no-store")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        log("netral server gagal: HTTP ${resp.code}")
                        false
                    } else {
                        val j = runCatching { JSONObject(text) }.getOrNull()
                        val n = j?.optInt("neutralized", 0) ?: 0
                        val total = j?.optInt("bots", 0) ?: 0
                        log(
                            if (n > 0) "server ($reason): $n/$total model dipaksa netral"
                            else "server ($reason): semua model sudah netral",
                        )
                        serverNeutralAt = System.currentTimeMillis()
                        true
                    }
                }
            } catch (e: Exception) {
                log("netral server gagal: ${e.message}")
                false
            }

            if (ok) {
                remaining = countBotsHolding()
                if (remaining == 0) return true
                if (remaining > 0) {
                    log("netral ulang ($reason): $remaining model masih punya posisi (harga feed belum ada)")
                }
            }
            if (attempt < NEUTRAL_ATTEMPTS) delay(NEUTRAL_RETRY_MS)
        }
        return remaining == 0
    }

    /** Jumlah bot Home yang masih memegang posisi/entry tertunda. -1 kalau tidak terbaca. */
    private suspend fun countBotsHolding(): Int = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${Prefs.baseUrl(ctx)}/api/snapshot")
                .header("cache-control", "no-store")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext -1
                val j = JSONObject(resp.body?.string().orEmpty())
                val arr = j.optJSONArray("bots") ?: return@withContext 0
                var n = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optBoolean("neutralizable", false) ||
                        o.optJSONObject("position") != null ||
                        o.optJSONObject("pending") != null
                    ) {
                        n++
                    }
                }
                n
            }
        } catch (_: Exception) {
            -1
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
        } catch (e: BinanceException) {
            // Kasus paling umum: API key dibatasi IP dan IP HP berubah → Binance
            // mengembalikan -2015 beserta IP-nya. Simpan supaya bisa disalin pengguna.
            fail(e)
            return
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
            } catch (e: BinanceException) {
                fail(e)
                return null
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
            if (m.side == 0) {
                // Model netral lagi: keluar dari daftar "aktif sekarang", tapi jawaban yang
                // sudah masuk window tetap disimpan (window memang mengumpulkan jawaban).
                liveSigs.remove(m.name)
                continue
            }
            val sig = Sig(m.side, now)
            latest[m.name] = sig
            liveSigs[m.name] = sig
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
     * Evaluasi window entry.
     *
     * Aturan revisi #31:
     *  - Skema yang **cocok** langsung di-ACC kapan pun (tidak menunggu 30 menit), termasuk
     *    skema yang hanya butuh beberapa model — jadi skema tanpa syarat 6/6 tetap bisa jalan.
     *  - Kalau belum ada yang cocok, window tetap diberi waktu sampai batas 30 menit; skema
     *    tidak dipotong waktunya hanya karena sinyal awal belum memenuhi.
     *  - 6/6 sudah menjawab tapi tidak ada skema cocok → langsung dinilai gagal (tidak menunggu).
     *  - Batas waktu habis → dinilai dengan sinyal yang ada, lalu aksi gagal dijalankan.
     */
    private suspend fun evaluateBatch(now: Long, snap: SnapshotData) {
        // Jeda (aksi COOLDOWN) berjalan sebelum window dimulai.
        if (cooldownUntil > 0) {
            if (now < cooldownUntil) return
            cooldownUntil = 0
            saveState()
        }
        // Tidak ada skema aktif = Auto sengaja tidak entry/lanjut. Diam saja, jangan
        // menggulir window terus-menerus.
        if (cfg.activeEntrySchemes.isEmpty()) {
            ui { BinanceState.autoBlock = "belum ada skema entry aktif — buat skema di tombol Atur" }
            if (!warnedNoScheme) {
                warnedNoScheme = true
                log("belum ada skema entry aktif — Auto menunggu skema dibuat di tombol Atur")
                notify("Belum ada skema entry aktif. Buat skema di menu Atur.")
            }
            return
        }
        warnedNoScheme = false
        if (batch.isEmpty()) return
        val total = modelNames.size.coerceAtLeast(1)
        val deadline = batchStart + entryWindowMs
        val counts = countSides(batch)
        val majority = if (counts.longs >= counts.shorts) 1 else -1
        val agree = max(counts.longs, counts.shorts)
        // Sinyal carry berasal dari window sebelumnya; model harus mengirim sinyal BARU
        // (ts > batchCarryAt) sebelum dianggap menjawab window ini.
        val allFresh = batch.values.all { it.ts > batchCarryAt }

        if (allFresh) {
            val hit = entrySchemeMatch(batch, majority, agree, total, snap)
            if (hit != null) {
                doEntry(
                    majority, cfg.entryLeverage, cfg.entryTp, cfg.entrySl,
                    now, snap, "${batch.size}/$total merespon · ${hit.name} · $agree selaras",
                )
                return
            }
        }
        val allAnswered = batch.size >= total && allFresh
        if (!allAnswered && now < deadline) return
        failEntry(entryFailReasons(batch, majority, agree, total, snap))
    }

    /**
     * Aksi saat skema entry gagal, mengikuti setelan aplikasi:
     *  - CARRY: bawa sinyal carry lalu window baru (default, perilaku #28).
     *  - RESET: netralkan semua model, window baru dari nol.
     *  - COOLDOWN: netralkan semua model lalu diam [AutoCommon.entryFailCooldownMin] menit.
     */
    private suspend fun failEntry(reason: String) {
        ui { BinanceState.autoBlock = reason }
        when (cfg.entryFailAction.uppercase()) {
            "RESET" -> {
                log("skema gagal ($reason) — netral semua model, window baru")
                neutralizeAllModels("window gagal")
                resetWindow()
            }
            "COOLDOWN" -> {
                val mins = cfg.entryFailCooldownMin.coerceAtLeast(1)
                log("skema gagal ($reason) — netral semua, jeda $mins menit")
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

    /**
     * Skema entry pertama yang **cocok** (logika OR). Null kalau tidak ada yang cocok,
     * atau kalau belum ada skema aktif.
     */
    private fun entrySchemeMatch(
        sigs: Map<String, Sig>,
        dir: Int,
        agree: Int,
        total: Int,
        snap: SnapshotData,
    ): SchemeRule? = cfg.activeEntrySchemes.firstOrNull { schemeFail(it, sigs, dir, agree, total, snap) == null }

    /** Alasan setiap skema entry gagal, untuk log & panel ("Entry ditahan: …"). */
    private fun entryFailReasons(
        sigs: Map<String, Sig>,
        dir: Int,
        agree: Int,
        total: Int,
        snap: SnapshotData,
    ): String {
        val schemes = cfg.activeEntrySchemes
        if (schemes.isEmpty()) return "belum ada skema entry aktif — buat skema di tombol Atur"
        warnedNoScheme = false
        return schemes.joinToString(" | ") { s ->
            "${s.name}: ${schemeFail(s, sigs, dir, agree, total, snap) ?: "cocok"}"
        }
    }

    /**
     * Satu skema cocok atau tidak. Dipakai untuk entry maupun recheck.
     * @return null kalau cocok, atau alasan gagal.
     */
    private fun schemeFail(
        scheme: SchemeRule,
        sigs: Map<String, Sig>,
        dir: Int,
        agree: Int,
        total: Int,
        snap: SnapshotData,
    ): String? {
        val required = if (scheme.requireAllModels) total else scheme.minResponded.coerceIn(1, total)
        if (sigs.size < required) return "baru ${sigs.size}/$required menjawab"
        if (agree < scheme.minAgree) return "selaras $agree (butuh ≥${scheme.minAgree})"
        profitSchemeFail(scheme, sigs, dir, snap)?.let { return it }
        silentProfitBlock(scheme, sigs, snap)?.let { return it }
        if (roiGapBlocks(scheme, dir, snap)) {
            return "gap ROI ≥${scheme.gapPct.toInt()}% (model teratas beda arah)"
        }
        return null
    }

    /** Kosongkan window dan kembali menunggu sinyal pertama (jeda tidak diubah). */
    private suspend fun resetWindow() {
        batch.clear()
        latest.clear()
        liveSigs.clear()
        batchStart = 0
        batchCarryAt = 0
        phase = Phase.IDLE
        saveState()
    }

    /**
     * Syarat profit memakai **ROI bot** tiap model dari /api/snapshot
     * (untung/rugi masing-masing model, bukan total gabungan):
     *  - tiap model tidak selaras wajib ROI ≤ [SchemeRule.misalignedMaxRoi] (default −35%);
     *  - minimal [SchemeRule.alignedMinCount] model selaras wajib ROI ≥ [SchemeRule.alignedMinRoi].
     */
    private fun profitSchemeFail(
        scheme: SchemeRule,
        sigs: Map<String, Sig>,
        dir: Int,
        snap: SnapshotData,
    ): String? {
        if (!scheme.profitRuleEnabled) return null
        if (snap.bots.isEmpty()) return "ROI model belum tersedia"
        val roiOf = snap.bots.associate { it.model to it.roiPct }
        val aligned = sigs.filterValues { it.side == dir }.keys
        val misaligned = sigs.filterValues { it.side != dir }.keys

        misaligned.forEach { name ->
            val roi = roiOf[name] ?: return "ROI $name belum tersedia"
            if (roi > scheme.misalignedMaxRoi) {
                return "$name tidak selaras ROI ${roi.toInt()}% (harus ≤ ${scheme.misalignedMaxRoi.toInt()}%)"
            }
        }
        val strong = aligned.count { (roiOf[it] ?: 0.0) >= scheme.alignedMinRoi }
        if (strong < scheme.alignedMinCount) {
            return "hanya $strong selaras ROI ≥${scheme.alignedMinRoi.toInt()}% " +
                "(butuh ${scheme.alignedMinCount})"
        }
        return null
    }

    /**
     * Opsional per skema: model yang **belum bersinyal** di window tapi ROI-nya di atas
     * [SchemeRule.silentProfitMax] ikut memblokir (kasus "5/6 selaras tapi yang belum
     * bersinyal sudah untung 50%").
     */
    private fun silentProfitBlock(
        scheme: SchemeRule,
        sigs: Map<String, Sig>,
        snap: SnapshotData,
    ): String? {
        if (!scheme.silentProfitBlockEnabled) return null
        val silent = modelNames.filter { it !in sigs.keys }
        if (silent.isEmpty()) return null
        val roiOf = snap.bots.associate { it.model to it.roiPct }
        silent.forEach { name ->
            val roi = roiOf[name] ?: return@forEach
            if (roi > scheme.silentProfitMax) {
                return "$name belum bersinyal, ROI ${roi.toInt()}% (> ${scheme.silentProfitMax.toInt()}%)"
            }
        }
        return null
    }

    /** Jumlah model yang berbalik arah melawan posisi (untuk aturan reversal saat recheck). */
    private fun reversalCount(sigs: Map<String, Sig>, dir: Int): Int =
        sigs.values.count { it.side == -dir }

    /**
     * Window gagal: bawa sinyal yang dijawab pada menit terakhir ([AutoCommon.carryMin])
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
     * - SL keras tetap dipasang/dipelihara di exchange.
     * - Patokan berikutnya BELUM berlaku sampai cek selesai (posisi bebas mencari untung).
     * - **Reversal dicek lebih dulu** (global): kalau cukup model berbalik arah, posisi dibalik.
     * - Lalu **skema recheck OR**: kalau ada satu skema aktif yang cocok → LANJUT;
     *   tidak ada yang cocok → aksi gagal (OUT / HOLD).
     * - Sampai batas waktu model tidak lengkap → aksi gagal.
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
        // Aturan #31: begitu syarat responden salah satu skema recheck terpenuhi (termasuk
        // 6/6), langsung dinilai tanpa menunggu. Selain itu tunggu sampai batas waktu, lalu
        // **tetap bandingkan** skema dengan sinyal yang ada.
        val ready = cfg.activeRecheckSchemes.any { s ->
            val need = if (s.requireAllModels) total else s.minResponded.coerceIn(1, total)
            responded >= need
        }
        if (!ready && now < recheckUntil) return

        // Reversal global: dicek sebelum skema, sesuai keputusan #30.
        val reversed = reversalCount(recheckSigs, direction)
        if (cfg.reversalEnabled && reversed >= cfg.reversalMin) {
            reversePosition(reversed, total, now, snap)
            return
        }

        val schemes = cfg.activeRecheckSchemes
        if (schemes.isEmpty()) {
            recheckFail("tidak ada skema recheck aktif", now)
            return
        }
        val aligned = recheckSigs.values.count { it.side == direction }
        val failures = ArrayList<String>()
        for (scheme in schemes) {
            val fail = schemeFail(scheme, recheckSigs, direction, aligned, total, snap)
            if (fail == null) {
                continueHolding(aligned, total, now)
                return
            }
            failures += "${scheme.name}: $fail"
        }
        val prefix = if (responded < total) {
            "cek ${cfg.recheckMin} menit habis: $responded/$total merespon · "
        } else {
            ""
        }
        recheckFail(prefix + failures.joinToString(" | "), now)
    }

    /** Skema recheck lolos → lanjutkan posisi yang SAMA dengan leverage & patokan berikutnya. */
    private suspend fun continueHolding(aligned: Int, total: Int, now: Long) {
        val client = api ?: return
        val newLev = nextLeverage
        val newTp = nextTp
        if (newLev != leverage) {
            try {
                client.setLeverage(SYMBOL, newLev)
                leverage = newLev
                // SL keras dihitung ulang karena leverage berubah (jarak harga berbeda)
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
     *  - HOLD: pertahankan posisi dan buka cek baru selama [AutoCommon.recheckMin] menit.
     */
    private suspend fun recheckFail(reason: String, now: Long) {
        ui { BinanceState.autoBlock = reason }
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
            val failed = ArrayList<String>()
            for (name in models) {
                var done = false
                for (attempt in 1..NEUTRAL_ATTEMPTS) {
                    try {
                        val body = JSONObject().put("id", name).toString()
                            .toRequestBody("application/json".toMediaType())
                        val req = Request.Builder()
                            .url("${Prefs.baseUrl(ctx)}/api/bots/neutral")
                            .post(body)
                            .header("cache-control", "no-store")
                            .build()
                        http.newCall(req).execute().use { resp ->
                            // 404 = tidak ada posisi/pending (atau id tidak dikenal) → tidak ada
                            // yang perlu dinetralkan, jadi dianggap selesai.
                            if (resp.isSuccessful || resp.code == 404) {
                                done = true
                            } else {
                                log("netral $name gagal: HTTP ${resp.code}")
                            }
                        }
                    } catch (e: Exception) {
                        log("netral $name gagal: ${e.message}")
                    }
                    if (done) break
                    if (attempt < NEUTRAL_ATTEMPTS) delay(NEUTRAL_RETRY_MS)
                }
                if (!done) failed += name
            }
            log(
                if (failed.isEmpty()) "server ($reason): ${models.size} model dipaksa netral"
                else "server ($reason): ${models.size - failed.size} netral, gagal: ${failed.joinToString(", ")}",
            )
        }
    }

    /**
     * Aturan NOTE per skema: kalau model dengan ROI tertinggi berbeda arah dari mayoritas dan
     * jarak ROI-nya ke peringkat kedua ≥ [SchemeRule.gapPct], skema tidak boleh lolos.
     */
    private fun roiGapBlocks(scheme: SchemeRule, dir: Int, snap: SnapshotData): Boolean {
        if (!scheme.gapRuleEnabled) return false
        if (snap.bots.size < 2) return false
        val sorted = snap.bots.sortedByDescending { it.roiPct }
        val top = sorted[0]
        val second = sorted[1]
        // Opsional: hanya berlaku kalau model teratas benar-benar profit.
        if (scheme.gapOnlyIfTopPositive && top.roiPct <= 0) return false
        if (top.roiPct - second.roiPct < scheme.gapPct) return false
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
        val margin = safeMargin(equity, account.availableBalance, pct, lev)
        if (margin <= 0.5) {
            fail("margin terlalu kecil (${money(margin)} USDT)")
            return
        }

        busy(true)
        try {
            client.setLeverage(SYMBOL, lev)
            val usedMargin = placeEntry(client, dir, margin, lev, price)
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
            liveSigs.clear()
            recheckSigs.clear()
            seeded = true
            lastProtectCheck = now
            ui { BinanceState.autoBlock = "" }

            log(
                "posisi ${if (dir > 0) "LONG" else "SHORT"} @ ${priceText(entry)} · margin ${money(usedMargin)} · " +
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
     * Margin yang aman dipakai: persen equity, tapi **disisakan untuk fee & slippage**.
     *
     * Dulu `availableBalance * 0.98` hanya menyisakan 2%, padahal fee taker saja sudah
     * `0.05% × leverage` dari margin (leverage 30 ≈ 1,5% equity) — belum harga geser antara
     * mark price dan harga fill. Akibatnya order lolos cek lokal tapi ditolak Binance dengan
     * "Margin is insufficient" (-2019), terutama saat saldo kecil (mis. 3 USDT).
     */
    private fun safeMargin(equity: Double, available: Double, pct: Double, lev: Int): Double {
        val desired = equity * pct / 100.0
        // Fee taker buka + tutup, plus slippage kecil sebagai cadangan.
        val feePerMargin = TAKER_FEE * lev * 2.0
        val reserveFactor = 1.0 - feePerMargin - SLIPPAGE_RESERVE
        val maxUsable = (available * reserveFactor).coerceAtLeast(0.0)
        return min(desired, maxUsable)
    }

    /**
     * Kirim market order entry dengan ukuran yang menyesuaikan penolakan Binance.
     *
     * Kalau exchange menolak karena margin/notional (mis. `-2019` "Margin is insufficient",
     * `-2013`, `-4003`, `-1013`), margin diperkecil lalu dicoba lagi — sampai
     * [ENTRY_SHRINK_TRIES] kali atau tidak ada ruang lagi.
     */
    private suspend fun placeEntry(
        client: BinanceApi,
        dir: Int,
        marginStart: Double,
        lev: Int,
        price: Double,
    ): Double {
        val rules = client.rules(SYMBOL)
        val side = if (dir > 0) "BUY" else "SELL"
        val posSide = if (client.hedgeMode) (if (dir > 0) "LONG" else "SHORT") else null
        var margin = marginStart
        var lastError: Exception? = null

        for (attempt in 1..ENTRY_SHRINK_TRIES) {
            val qtyText = client.formatQty(margin * lev / price, rules)
            val qty = qtyText.toDoubleOrNull() ?: 0.0
            if (qty <= 0 || qty * price < rules.minNotional) {
                throw IllegalStateException(
                    "qty di bawah minimum (${rules.minNotional} USDT) — " +
                        "margin ${money(margin)} × ${lev}x = ${money(margin * lev)} USDT",
                )
            }
            try {
                log("entry ${if (dir > 0) "LONG" else "SHORT"} ${lev}x · margin ${money(margin)} · qty $qtyText")
                client.marketOrder(SYMBOL, side, qtyText, clientId("e"), positionSide = posSide)
                return margin
            } catch (e: BinanceException) {
                if (e.code !in SHRINKABLE_CODES) throw e
                lastError = e
                val next = margin * 0.8
                log("order ditolak (${e.code}: ${e.message}) — coba lagi dengan margin ${money(next)}")
                if (next * lev < rules.minNotional) {
                    throw IllegalStateException("margin ${money(margin)} tidak cukup: ${e.message}")
                }
                margin = next
            }
        }
        throw lastError ?: IllegalStateException("entry gagal")
    }

    /**
     * Hanya SL terdalam di exchange ([AutoCommon.maxSl], default -40% ROI dari entry) —
     * pengaman kalau aplikasi mati. Level lainnya dikelola engine sebagai patokan.
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
     * **level patokan** aplikasi.
     *
     * Konsep: level-level ini PATOKAN (checkpoint), bukan order TP/SL sungguhan. Menyentuh
     * patokan TIDAK menutup posisi — hanya memicu cek model.
     *
     * Pengecualian (lihat [isRealTp] / [isRealSl]):
     *  - kalau "peningkatan ROI" **OFF** dan level terakhir tersentuh → TP/SL **asli** (keluar).
     *  - SL terdalam selalu jadi SL **asli** di exchange.
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
            if (isRealTp(tpLevel)) {
                closeAtRealTp(roi)
            } else {
                startRecheck("TP", roi, now)
            }
        } else if (roi <= -slLevel) {
            if (isRealSl(slLevel) || slLevel >= hardSlRoi) {
                closeAtRealSl(roi)
            } else {
                startRecheck("SL", roi, now)
            }
        }
    }

    /** Level TP ini adalah TP **asli** (bukan patokan)? */
    private fun isRealTp(level: Double): Boolean {
        if (cfg.tpExtend) return false
        val last = cfg.tpLadder.lastOrNull() ?: return false
        return level >= last
    }

    /** Level SL ini adalah SL **asli** (bukan patokan)? */
    private fun isRealSl(level: Double): Boolean {
        if (level >= hardSlRoi) return true
        if (cfg.slExtend) return false
        val last = cfg.slLadder.lastOrNull() ?: return false
        return level >= last
    }

    /** TP asli kena: tutup posisi sekarang, netralkan model, mulai window baru. */
    private suspend fun closeAtRealTp(roi: Double) {
        log("TP asli +${tpLevel.toInt()}% kena (ROI ${roi.toInt()}%) — posisi ditutup")
        notify("TP asli +${tpLevel.toInt()}% kena. Posisi ditutup.")
        busy(true)
        try {
            closeIfOpen(api)
        } finally {
            busy(false)
        }
        neutralizeAllModels("TP asli +${tpLevel.toInt()}%")
        phase = Phase.IDLE
        clearState()
        saveState()
    }

    /** SL asli kena: tutup posisi + netralkan model. */
    private suspend fun closeAtRealSl(roi: Double) {
        log("SL asli -${slLevel.toInt()}% kena (ROI ${roi.toInt()}%) — posisi ditutup, keluar total")
        notify("SL asli -${slLevel.toInt()}% kena. Posisi ditutup.")
        busy(true)
        try {
            closeIfOpen(api)
        } finally {
            busy(false)
        }
        neutralizeAllModels("SL asli -${slLevel.toInt()}%")
        phase = Phase.IDLE
        clearState()
        saveState()
    }

    /**
     * Patokan tersentuh → MULAI cek maksimal [AutoCommon.recheckMin] menit. Posisi TIDAK ditutup.
     * Keenam model dinetralkan supaya bisa mencari pola baru; begitu cukup model menjawab,
     * hasilnya dinilai: lanjut (skema lolos), berbalik arah (reversal), atau keluar/ditahan
     * sesuai [AutoCommon.recheckFailAction].
     */
    private suspend fun startRecheck(kind: String, roi: Double, now: Long) {
        val dirText = if (direction > 0) "LONG" else "SHORT"
        val hitLevel = if (kind == "TP") tpLevel else slLevel

        // SL terdalam selalu keluar total, tidak ada cek lanjut
        if (kind == "SL" && hitLevel >= hardSlRoi) {
            closeAtRealSl(roi)
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
     * Patokan TP berikutnya. Kalau [AutoCommon.tpExtend] ON, setelah angka terakhir naik
     * terus +[AutoCommon.tpStep] (tidak ada TP asli). Kalau OFF, angka terakhir dipertahankan
     * sebagai TP asli.
     */
    private fun nextTpAfter(current: Double): Double {
        val next = cfg.tpLadder.firstOrNull { it > current }
        if (next != null) return next
        return if (cfg.tpExtend) current + cfg.tpStep else current
    }

    /**
     * Leverage berikutnya dari [AutoCommon.leverageLadder]; kalau tangga habis, naik
     * [AutoCommon.leverageStep] sampai [AutoCommon.maxLeverage].
     */
    private fun nextLeverageAfter(current: Int): Int =
        cfg.leverageLadder.firstOrNull { it > current }
            ?: min(current + cfg.leverageStep, cfg.maxLeverage)

    /**
     * Patokan SL berikutnya. Kalau [AutoCommon.slExtend] ON, setelah angka terakhir melebar
     * terus +[AutoCommon.slStep]. Kalau OFF, angka terakhir dipertahankan sebagai SL asli.
     */
    private fun nextSlAfter(current: Double): Double {
        val next = cfg.slLadder.firstOrNull { it > current }
        if (next != null) return next
        return if (cfg.slExtend) min(current + cfg.slStep, 100.0) else current
    }

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
                // Margin manual dihormati apa adanya, tapi dicek dulu terhadap saldo yang
                // benar-benar bisa dipakai (fee & slippage disisakan) supaya tidak ditolak
                // Binance dengan "Margin is insufficient" tanpa penjelasan.
                val maxUsable = account.availableBalance * (1.0 - TAKER_FEE * lev * 2.0 - SLIPPAGE_RESERVE)
                if (marginUsd > maxUsable) {
                    throw IllegalStateException(
                        "margin ${money(marginUsd)} melebihi batas ${money(maxUsable)} USDT " +
                            "(available ${money(account.availableBalance)}, fee ${lev}x disisakan)",
                    )
                }
                val qtyText = client.formatQty(marginUsd * lev / price, rules)
                val qty = qtyText.toDoubleOrNull() ?: 0.0
                if (qty <= 0 || qty * price < rules.minNotional) {
                    throw IllegalStateException(
                        "margin ${money(marginUsd)} × ${lev}x = ${money(marginUsd * lev)} USDT, " +
                            "di bawah notional minimum ${money(rules.minNotional)} USDT",
                    )
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
        liveSigs.clear()
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
            BinanceState.blockedIp = null
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
                // Angka "respon" = model yang bersinyal SEKARANG (sama dengan Home).
                // Jawaban yang sudah masuk window tetap dinilai (lihat batch).
                responded = liveSigs.size
                aligned = max(
                    liveSigs.values.count { it.side > 0 },
                    liveSigs.values.count { it.side < 0 },
                )
                "Sinyal $responded/$total aktif · ${batch.size}/$total terkumpul · " +
                    "sisa ${mmss(batchStart + entryWindowMs - now)}"
            }
            Phase.HOLDING -> {
                responded = modelNames.count { (current[it] ?: 0) != 0 }
                aligned = modelNames.count { current[it] == direction }
                "${if (direction > 0) "LONG" else "SHORT"} ${leverage}x · ROI ${lastRoi.toInt()}% · target +${tpLevel.toInt()}% / -${slLevel.toInt()}%"
            }
            Phase.RECHECK -> {
                // Angka "aktif" mengikuti Home; jawaban yang terkumpul tetap dipakai menilai.
                responded = liveSigs.size
                aligned = liveSigs.values.count { it.side == direction }
                val reversed = liveSigs.values.count { it.side == -direction }
                val hit = if (recheckKind == "TP") "+${tpLevel.toInt()}%" else "-${slLevel.toInt()}%"
                "Posisi MASIH JALAN ${leverage}x · ROI ${lastRoi.toInt()}% · model $responded/$total aktif " +
                    "(${recheckSigs.size} terkumpul) · balik $reversed · cek $hit sisa ${mmss(recheckUntil - now)}"
            }
            Phase.IDLE -> {
                responded = modelNames.count { (current[it] ?: 0) != 0 }
                if (cooldownUntil > now) {
                    "Jeda entry ${mmss(cooldownUntil - now)} · window ${cfg.entryWindowMin} menit"
                } else {
                    "Menunggu sinyal pertama · window ${cfg.entryWindowMin} menit"
                }
            }
        }
        ui {
            BinanceState.autoPhase = phase.name
            BinanceState.autoDetail = detail
            BinanceState.autoSchemes = cfg.activeEntrySchemes.size
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

    /**
     * Gagal konek karena alasan Binance. Kalau errornya soal API key/IP, IP yang dilihat
     * Binance disimpan supaya bisa disalin ke whitelist (tombol "Salin IP" di tab Binance).
     */
    private fun fail(e: BinanceException) {
        val ip = e.requestIp
        ui {
            BinanceState.connected = false
            BinanceState.lastError = e.message
            BinanceState.blockedIp = ip
            BinanceState.lastUpdate = System.currentTimeMillis()
        }
        if (e.isIpBlock) {
            log("Binance menolak API key/IP (${e.code}): ${e.message}")
            // Sebagian pesan Binance tidak menyertakan IP. Ambil IP publik sendiri supaya
            // pengguna tetap bisa menyalinnya ke whitelist.
            if (ip == null) scope.launch { resolvePublicIp() }
        }
    }

    /** Ambil IP publik perangkat lewat layanan teks polos (fallback saat Binance diam). */
    private suspend fun resolvePublicIp() {
        for (url in listOf("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com")) {
            try {
                val req = Request.Builder().url(url).header("cache-control", "no-store").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val ip = resp.body?.string()?.trim().orEmpty()
                    if (IPV4.matches(ip)) {
                        ui { if (BinanceState.blockedIp == null) BinanceState.blockedIp = ip }
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Tombol "Cek IP saya" di tab Binance: ambil IP publik perangkat sekarang juga,
     * supaya bisa disalin ke whitelist Binance tanpa menunggu error -2015.
     */
    fun checkPublicIp() {
        if (BinanceState.ipLoading) return
        ui { BinanceState.ipLoading = true }
        scope.launch {
            try {
                for (url in listOf("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com")) {
                    try {
                        val req = Request.Builder().url(url).header("cache-control", "no-store").build()
                        http.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use
                            val ip = resp.body?.string()?.trim().orEmpty()
                            if (IPV4.matches(ip)) {
                                ui { BinanceState.blockedIp = ip }
                                return@launch
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                log("gagal mengambil IP publik (tidak ada koneksi ke layanan IP)")
            } finally {
                ui { BinanceState.ipLoading = false }
            }
        }
    }

    private fun busy(value: Boolean) {
        ui { BinanceState.busy = value }
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }
}
