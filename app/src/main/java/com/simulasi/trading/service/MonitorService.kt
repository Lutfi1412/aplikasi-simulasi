package com.simulasi.trading.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.simulasi.trading.binance.AutoEngine
import com.simulasi.trading.data.BinanceState
import com.simulasi.trading.data.BinanceStore
import com.simulasi.trading.data.PatokanStore
import com.simulasi.trading.data.Prefs
import com.simulasi.trading.data.ServiceState
import com.simulasi.trading.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Foreground service yang tetap hidup ketika aplikasi di latar belakang.
 *
 * Tugasnya: polling /api/signals tiap 10 detik (payload ±1 KB, hemat kuota),
 * patokan harga, lalu memunculkan notifikasi + suara (notif.mp3 + TTS).
 * Tampilan penuh tetap memakai WebView yang menyajikan UI web yang sama.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val autoEngine by lazy { AutoEngine(this) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var pollJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.createChannels(this)
        Notifier.prepare(this)

        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                main.post { ServiceState.enabled = false }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_SOUND -> {
                Notifier.setSoundEnabled(this, !Prefs.soundEnabled(this))
            }
        }

        if (!Prefs.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = Notifier.buildService(
            this,
            "menyiapkan pemantauan…",
            Prefs.soundEnabled(this),
        )
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, Notifier.SERVICE_NOTIF_ID, notification, type)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            main.post {
                ServiceState.enabled = true
                ServiceState.soundEnabled = Prefs.soundEnabled(this@MonitorService)
            }
            ensureWakeLock()
            pollJob = scope.launch {
                var failures = 0
                while (isActive && running) {
                    ensureWakeLock()
                    val ok = pollOnce()
                    failures = if (ok) 0 else failures + 1
                    Notifier.updateService(
                        this@MonitorService,
                        if (ok) statusText() else "tidak tersambung · mencoba lagi",
                    )
                    delay(if (ok) 10_000L else min(30_000L, 5_000L * (failures + 1)))
                }
            }
        } else {
            Notifier.updateService(this, statusText())
        }

        // Eksekutor Binance hanya hidup selama layanan latar belakang berjalan.
        autoEngine.start()

        return START_STICKY
    }

    private fun pollOnce(): Boolean {
        val request = try {
            Request.Builder()
                .url("${Prefs.baseUrl(this)}/api/signals")
                .header("cache-control", "no-store")
                .build()
        } catch (_: Exception) {
            main.post {
                ServiceState.connected = false
                ServiceState.lastError = "URL server tidak valid"
            }
            return false
        }

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) throw IOException("respons kosong")
                handleSnapshot(JSONObject(body))
                main.post {
                    ServiceState.connected = true
                    ServiceState.lastError = null
                    ServiceState.lastUpdate = System.currentTimeMillis()
                }
                true
            }
        } catch (e: Exception) {
            main.post {
                ServiceState.connected = false
                ServiceState.lastError = e.message
                ServiceState.lastUpdate = System.currentTimeMillis()
            }
            false
        }
    }

    private fun handleSnapshot(snapshot: JSONObject) {
        // /api/signals mengirim harga di root; /api/snapshot (server lama) di market.price.
        val rootPrice = snapshot.optDouble("price", Double.NaN)
        val marketPrice = snapshot.optJSONObject("market")?.optDouble("price", Double.NaN) ?: Double.NaN
        val price = if (rootPrice.isFinite()) rootPrice else marketPrice
        if (price.isFinite() && price > 0) {
            Prefs.setLastPrice(this, price)
            main.post { ServiceState.price = price }
        }

        detectSignals(snapshot, price)
        checkPatokan(price)
    }

    private fun detectSignals(snapshot: JSONObject, price: Double) {
        val array = snapshot.optJSONArray("signals") ?: return
        val current = LinkedHashMap<String, JSONObject>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val botId = item.optString("botId", item.optString("model", "-"))
            val ts = item.optLong("ts", 0L)
            val side = item.optInt("side", 0)
            current["$botId-$ts-$side"] = item
        }

        if (!Prefs.isSeeded(this)) {
            Prefs.setSignalKeys(this, current.keys.toList().takeLast(300).toSet())
            Prefs.setSeeded(this, true)
            return
        }

        val seen = Prefs.signalKeys(this)
        val fresh = current.entries.filter { it.key !in seen }
        if (fresh.isNotEmpty() && !Prefs.appVisible(this)) {
            // array sinyal biasanya terbaru di atas; bunyikan dari yang paling lama
            for ((_, item) in fresh.reversed().takeLast(6)) {
                Notifier.fireSignal(
                    this,
                    item.optString("model", "model"),
                    item.optInt("side", 1),
                    item.optDouble("price", price),
                )
            }
        }

        val merged = LinkedHashSet(seen)
        merged.addAll(current.keys)
        Prefs.setSignalKeys(this, merged.toList().takeLast(300).toSet())
    }

    private fun checkPatokan(price: Double) {
        if (!price.isFinite() || price <= 0) return
        val list = PatokanStore.load(this)
        for (patokan in list) {
            if (patokan.status != "waiting" || !patokan.isReached(price)) continue
            PatokanStore.markReached(this, patokan.id)
            if (!Prefs.appVisible(this)) Notifier.firePatokan(this, patokan, price)
        }
    }

    private fun statusText(): String {
        val price = ServiceState.price ?: Prefs.lastPrice(this).takeIf { it > 0 }
        val clock = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }.format(Date())
        val priceText = price?.let { Notifier.formatPrice(it) } ?: "—"
        val auto = if (BinanceStore.autoEnabled(this)) {
            " · auto ${BinanceState.autoPhase.lowercase(Locale.US)}"
        } else {
            ""
        }
        return "SOL $priceText · latar belakang aktif$auto · $clock WIB"
    }

    private fun ensureWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimulasiTrading:poll").apply {
                setReferenceCounted(false)
            }
        }
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            try {
                lock.acquire(10 * 60 * 1000L)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        running = false
        pollJob?.cancel()
        autoEngine.stop()
        scope.cancel()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        main.post {
            ServiceState.connected = false
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.simulasi.trading.STOP"
        const val ACTION_TOGGLE_SOUND = "com.simulasi.trading.TOGGLE_SOUND"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}
