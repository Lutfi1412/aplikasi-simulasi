package com.simulasi.trading.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog as PlatformAlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simulasi.trading.data.Patokan
import com.simulasi.trading.data.PatokanStore
import com.simulasi.trading.data.Prefs
import com.simulasi.trading.data.ServiceState
import com.simulasi.trading.notify.Notifier
import com.simulasi.trading.service.MonitorService
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import org.json.JSONObject

private val Bg = Color(0xFF0B0D10)
private val Bar = Color(0xFF11151A)
private val Line = Color(0xFF232A33)
private val Mute = Color(0xFF8B949E)
private val Long = Color(0xFF22C55E)
private val Short = Color(0xFFEF4444)
private val Amber = Color(0xFFF59E0B)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var baseUrl by remember { mutableStateOf(Prefs.baseUrl(context)) }
    var reloadAt by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showPatokan by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        if (tab != 0) {
            tab = 0
        } else {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else (context as? Activity)?.finish()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }

    LaunchedEffect(webView, baseUrl, reloadAt) {
        webView?.loadUrl(baseUrl)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = if (tab == 0) Modifier.fillMaxSize() else Modifier.size(0.dp),
            ) {
                NativeBar(
                    baseUrl = baseUrl,
                    onToggleService = { setServiceEnabled(context, !Prefs.isEnabled(context)) },
                    onOpenPatokan = { showPatokan = true },
                    onOpenSettings = { showSettings = true },
                )
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx -> buildWebView(ctx).also { webView = it } },
                )
            }
            BinanceScreen(
                modifier = if (tab == 1) Modifier.fillMaxSize() else Modifier.size(0.dp),
            )
        }
        BottomNav(current = tab, onSelect = { tab = it })
    }

    if (showSettings) {
        SettingsDialog(
            onClose = { showSettings = false },
            onSaved = { url ->
                baseUrl = url
                reloadAt++
            },
        )
    }
    if (showPatokan) {
        PatokanDialog(
            currentPrice = ServiceState.price,
            webView = webView,
            onClose = { showPatokan = false },
        )
    }
}

@Composable
private fun BottomNav(current: Int, onSelect: (Int) -> Unit) {
    Column {
        HorizontalDivider(color = Line)
        Row(
            modifier = Modifier.fillMaxWidth().height(54.dp).background(Bar),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((index, label) in listOf(0 to "Home", 1 to "Binance")) {
                val active = current == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(6.dp).clip(CircleShape).background(
                            if (active) Color(0xFF22D3EE) else Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        color = if (active) Color(0xFF22D3EE) else Mute,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeBar(
    baseUrl: String,
    onToggleService: () -> Unit,
    onOpenPatokan: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val enabled = ServiceState.enabled
    val connected = ServiceState.connected
    val price = ServiceState.price
    val dot = when {
        !enabled -> Short
        connected -> Long
        else -> Amber
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(Bar)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1B222B)),
            contentAlignment = Alignment.Center,
        ) {
            Text("S6", color = Color(0xFFE5E7EB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (!enabled) "MATI" else if (connected) "AKTIF" else "MENYAMBUNG",
                    color = dot,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (price != null && price > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "SOL ${formatPrice(price)}",
                        color = Color(0xFFE5E7EB),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                text = baseUrl.substringAfter("://"),
                color = Mute,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onOpenPatokan, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("Patokan", fontSize = 10.sp, color = Mute)
        }
        TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("Set", fontSize = 10.sp, color = Mute)
        }
        TextButton(onClick = onToggleService, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(
                text = if (enabled) "OFF" else "ON",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Long else Short,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(context: Context): WebView {
    return WebView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        addJavascriptInterface(NativeBridge(context), "SimulasiNative")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(PATOKAN_SYNC_JS, null)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (view == null || result == null) return false
                PlatformAlertDialog.Builder(view.context)
                    .setMessage(message)
                    .setPositiveButton("Ya") { _, _ -> result.confirm() }
                    .setNegativeButton("Batal") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (view == null || result == null) return false
                PlatformAlertDialog.Builder(view.context)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
    }
}

@Composable
private fun SettingsDialog(onClose: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(Prefs.baseUrl(context)) }
    var sound by remember { mutableStateOf(Prefs.soundEnabled(context)) }
    var enabled by remember { mutableStateOf(Prefs.isEnabled(context)) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pengaturan") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("URL server", fontSize = 10.sp, color = Mute, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Suara notifikasi", fontSize = 12.sp)
                        Text("notif.mp3 + ucapan sinyal / patokan", fontSize = 9.sp, color = Mute)
                    }
                    Switch(checked = sound, onCheckedChange = { sound = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Layanan latar belakang", fontSize = 12.sp)
                        Text("polling tiap 10 detik, tetap jalan saat layar mati", fontSize = 9.sp, color = Mute)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { openBatterySettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Izinkan jalan di latar belakang", fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "URL quick tunnel Cloudflare bisa berubah setelah tunnel di-restart atau VPS reboot. " +
                        "Kalau web gagal dibuka, salin URL terbaru dari VPS (~/simulasi-url.txt) ke kolom di atas.",
                    fontSize = 9.sp,
                    color = Mute,
                )
                Text(
                    "Versi aplikasi 1.0.1 · tampilan web tetap sama seperti di browser; patokan bisa " +
                        "ditambah, diubah, dan dihapus langsung dari tombol Patokan di bar atas.",
                    fontSize = 9.sp,
                    color = Mute,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.setBaseUrl(context, url)
                Notifier.setSoundEnabled(context, sound)
                setServiceEnabled(context, enabled)
                onSaved(Prefs.baseUrl(context))
                onClose()
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Batal") } },
    )
}

@Composable
private fun PatokanDialog(currentPrice: Double?, webView: WebView?, onClose: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(PatokanStore.load(context)) }
    var input by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("below") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resetForm() {
        input = ""
        direction = "below"
        editingId = null
        error = null
    }

    fun commit(next: List<Patokan>) {
        PatokanStore.save(context, next)
        items = PatokanStore.load(context)
        pushPatokansToWeb(webView, items)
    }

    fun submit() {
        val value = parsePatokanInput(input)
        if (value == null) {
            error = "isi angka harga yang valid (contoh 88 atau 90,5)"
            return
        }
        val id = editingId
        val next = if (id != null) {
            items.map {
                if (it.id == id) it.copy(value = value, direction = direction, status = "waiting") else it
            }
        } else {
            listOf(
                Patokan(
                    id = UUID.randomUUID().toString(),
                    value = value,
                    direction = direction,
                    status = "waiting",
                    createdAt = System.currentTimeMillis(),
                ),
            ) + items
        }
        commit(next)
        resetForm()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            items = PatokanStore.load(context)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp),
            shape = RoundedCornerShape(12.dp),
            color = Bar,
        ) {
            Column(modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Patokan harga",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE5E7EB),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "harga ${currentPrice?.let { formatPrice(it) } ?: "—"}",
                        fontSize = 10.sp,
                        color = Mute,
                    )
                    TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Tutup", fontSize = 10.sp, color = Mute)
                    }
                }
                Spacer(Modifier.height(6.dp))

                if (items.isEmpty()) {
                    Text(
                        "Belum ada patokan. Tambahkan satu atau beberapa angka di bawah.",
                        fontSize = 10.sp,
                        color = Mute,
                    )
                } else {
                    items.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.label(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE5E7EB),
                                )
                                Text(
                                    text = if (p.status == "reached") "tercapai" else "menunggu",
                                    fontSize = 9.sp,
                                    color = if (p.status == "reached") Long else Amber,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            TextButton(
                                onClick = {
                                    editingId = p.id
                                    input = formatInputValue(p.value)
                                    direction = p.direction
                                    error = null
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) { Text("edit", fontSize = 10.sp, color = Mute) }
                            TextButton(
                                onClick = {
                                    commit(items.filterNot { it.id == p.id })
                                    if (editingId == p.id) resetForm()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) { Text("hapus", fontSize = 10.sp, color = Short) }
                        }
                        HorizontalDivider(color = Line)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    if (editingId != null) "UBAH HARGA PATOKAN (USDT)" else "TAMBAH HARGA PATOKAN (USDT)",
                    fontSize = 9.sp,
                    color = Mute,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 13.sp),
                    placeholder = { Text("mis. 88 atau 90,5", fontSize = 12.sp, color = Mute) },
                )

                Spacer(Modifier.height(8.dp))
                Text("ARAH", fontSize = 9.sp, color = Mute, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth()) {
                    for ((value, label) in listOf("below" to "di bawah", "above" to "di atas")) {
                        val selected = direction == value
                        TextButton(
                            onClick = { direction = value },
                            modifier = Modifier
                                .weight(1f)
                                .background(if (selected) Color(0xFF1B222B) else Color.Transparent),
                        ) {
                            Text(
                                label,
                                fontSize = 11.sp,
                                color = if (selected) Color(0xFFE5E7EB) else Mute,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Text(
                    if (direction == "below") {
                        "aktif saat harga ≤ patokan (harga turun menyentuh angka)."
                    } else {
                        "aktif saat harga ≥ patokan (harga naik menyentuh angka)."
                    },
                    fontSize = 9.sp,
                    color = Mute,
                )

                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, fontSize = 10.sp, color = Short)
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { submit() }, modifier = Modifier.weight(1f)) {
                        Text(
                            if (editingId != null) "Simpan perubahan" else "Tambah patokan",
                            fontSize = 11.sp,
                        )
                    }
                    if (editingId != null) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { resetForm() }) { Text("Batal", fontSize = 11.sp) }
                    }
                }
                if (items.any { it.status == "reached" }) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { commit(items.filterNot { it.status == "reached" }) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Hapus semua yang tercapai", fontSize = 10.sp, color = Mute) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bisa lebih dari satu. Tiap patokan berbunyi 3× sekali saat tercapai, lalu diam. " +
                        "Daftar ini ikut tersinkron ke halaman web dan layanan latar belakang.",
                    fontSize = 9.sp,
                    color = Mute,
                )
            }
        }
    }
}

/** Jembatan WebView ↔ native: patokan dari localStorage dan suara TTS aplikasi. */
private class NativeBridge(private val context: Context) {
    @JavascriptInterface
    fun syncPatokans(json: String?) {
        if (json.isNullOrBlank()) return
        PatokanStore.syncFromWeb(context.applicationContext, json)
    }

    /**
     * WebView tidak punya Web Speech API. Halaman web memanggil ini untuk memutar
     * notif.mp3 + TTS native (sinyal, patokan, dan tombol Tes suara).
     */
    @JavascriptInterface
    fun alert(text: String?, cycles: Int, speechRepeats: Int) {
        if (text.isNullOrBlank()) return
        Notifier.playInApp(context.applicationContext, text, cycles, speechRepeats)
    }
}

/** Kirim daftar patokan hasil edit native ke localStorage halaman web. */
private fun pushPatokansToWeb(view: WebView?, list: List<Patokan>) {
    if (view == null) return
    val json = PatokanStore.toJson(list)
    val js = """
        (function () {
          try {
            var value = ${JSONObject.quote(json)};
            if (value === '[]') localStorage.removeItem('sim-patokan-list');
            else localStorage.setItem('sim-patokan-list', value);
            window.dispatchEvent(new Event('sim-patokan-changed'));
          } catch (e) {}
        })();
    """.trimIndent()
    view.post { view.evaluateJavascript(js, null) }
}

private fun parsePatokanInput(text: String): Double? {
    val raw = text.trim().replace(" ", "")
    if (raw.isEmpty()) return null
    // terima "88,5" (koma desimal) maupun "88.5"/"1.234,56"
    val normalized = if (raw.contains(',')) raw.replace(".", "").replace(',', '.') else raw
    val value = normalized.toDoubleOrNull() ?: return null
    return if (value.isFinite() && value > 0) value else null
}

private fun formatInputValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)

/**
 * Dipasang setelah halaman web selesai dimuat. Setiap 2 detik daftar patokan
 * localStorage (`sim-patokan-list`) dikirim ke layanan latar belakang.
 */
private const val PATOKAN_SYNC_JS = """
(function () {
  if (window.__simPatokanBridge) return;
  window.__simPatokanBridge = true;
  var last = null;
  function push() {
    try {
      var value = localStorage.getItem('sim-patokan-list');
      if (value === null) value = '[]';
      if (value !== last) {
        last = value;
        if (window.SimulasiNative) window.SimulasiNative.syncPatokans(value);
      }
    } catch (e) {}
  }
  setInterval(push, 2000);
  push();
})();
"""

private fun setServiceEnabled(context: Context, enabled: Boolean) {
    Prefs.setEnabled(context, enabled)
    ServiceState.enabled = enabled
    if (enabled) MonitorService.start(context) else MonitorService.stop(context)
}

private fun openBatterySettings(context: Context) {
    try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (context !is Activity) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        } catch (_: Exception) {
        }
    }
}

private fun formatPrice(value: Double): String =
    if (value.isFinite() && value > 0) String.format(Locale("id", "ID"), "%,.2f", value) else "—"
