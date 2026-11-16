package com.simulasi.trading.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simulasi.trading.binance.AutoEngine
import com.simulasi.trading.data.BinanceState
import com.simulasi.trading.data.BinanceStore
import com.simulasi.trading.data.ServiceState
import com.simulasi.trading.service.MonitorService
import java.util.Locale

private val CBg = Color(0xFF0B0D10)
private val CBar = Color(0xFF11151A)
private val CLine = Color(0xFF232A33)
private val CMute = Color(0xFF8B949E)
private val CText = Color(0xFFE5E7EB)
private val CGreen = Color(0xFF22C55E)
private val CRed = Color(0xFFEF4444)
private val CAmber = Color(0xFFF59E0B)
private val CCyan = Color(0xFF22D3EE)

@Composable
fun BinanceScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf(!BinanceState.configured) }
    var apiKey by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var cfgVersion by remember { mutableStateOf(0) }

    var margin by remember { mutableStateOf("10") }
    var lev by remember { mutableFloatStateOf(30f) }
    var tpInput by remember { mutableStateOf("22") }
    var slInput by remember { mutableStateOf("22") }

    LaunchedEffect(Unit) {
        BinanceStore.loadCredentials(ctx)?.let {
            apiKey = it.apiKey
            secret = it.secretKey
            comment = it.comment
            if (comment.isBlank()) comment = BinanceState.comment
        }
    }

    fun saveKey() {
        if (apiKey.isBlank() || secret.isBlank()) {
            notice = "API key dan secret wajib diisi"
            return
        }
        val ok = BinanceStore.saveCredentials(ctx, apiKey.trim(), secret.trim(), comment.trim())
        if (!ok) {
            notice = "Gagal menyimpan ke Android Keystore"
            return
        }
        BinanceState.configured = true
        BinanceState.comment = comment.trim()
        editing = false
        notice = "API key tersimpan di aplikasi"
    }

    fun setApi(on: Boolean) {
        if (on) {
            if (!BinanceState.configured) {
                notice = "Simpan API key dulu"
                return
            }
            if (!ServiceState.enabled) {
                notice = "Nyalakan layanan latar belakang di tab Home dulu"
                return
            }
            BinanceStore.setApiEnabled(ctx, true)
            BinanceState.apiEnabled = true
            MonitorService.start(ctx)
            notice = null
        } else {
            BinanceStore.setApiEnabled(ctx, false)
            BinanceStore.setAutoEnabled(ctx, false)
            BinanceState.apiEnabled = false
            BinanceState.autoEnabled = false
            AutoEngine.instance?.onAutoDisabled()
            BinanceState.resetConnection()
            notice = "API Binance dimatikan"
        }
    }

    fun setAuto(on: Boolean) {
        if (on) {
            if (!BinanceState.configured) {
                notice = "Simpan API key dulu"
                return
            }
            if (!ServiceState.enabled) {
                notice = "Nyalakan layanan latar belakang di tab Home dulu"
                return
            }
            if (!BinanceState.apiEnabled) {
                BinanceStore.setApiEnabled(ctx, true)
                BinanceState.apiEnabled = true
                MonitorService.start(ctx)
            }
            BinanceStore.setAutoEnabled(ctx, true)
            BinanceState.autoEnabled = true
            AutoEngine.instance?.onAutoEnabled()
            notice = "Auto ON — model netral, posisi (jika ada) diadopsi"
        } else {
            BinanceStore.setAutoEnabled(ctx, false)
            BinanceState.autoEnabled = false
            AutoEngine.instance?.onAutoDisabled()
            notice = "Auto OFF"
        }
    }

    fun submitOrder(dir: Int) {
        val m = margin.trim().replace(',', '.').toDoubleOrNull()
        if (m == null || m <= 0) {
            notice = "Margin tidak valid"
            return
        }
        val tpv = tpInput.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        val slv = slInput.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        val engine = AutoEngine.instance
        if (engine == null) {
            notice = "Layanan Binance belum siap"
            return
        }
        notice = "Mengirim market order…"
        engine.manualOpen(dir, m, lev.toInt(), tpv, slv) { err ->
            main.post { notice = err ?: "Order terkirim" }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CBg)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            "Binance",
            color = CText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Futures SOLUSDT · market order · kunci hanya di HP ini",
            color = CMute,
            fontSize = 10.sp,
        )
        notice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = CAmber, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))

        // ---------------------------------------------------------- kredensial
        CardBox {
            SectionTitle("API BINANCE")
            if (!editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (BinanceState.apiEnabled) CGreen else CMute),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        BinanceState.comment.ifBlank { "API key tersimpan" },
                        color = CText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(maskKey(apiKey), color = CMute, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Row {
                    TextButton(onClick = { editing = true }) {
                        Text("Ubah", fontSize = 11.sp, color = CCyan)
                    }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Hapus", fontSize = 11.sp, color = CRed)
                    }
                }
            } else {
                Field("API key", apiKey, { apiKey = it }, password = false)
                Spacer(Modifier.height(6.dp))
                Field("Secret key", secret, { secret = it }, password = !showSecret)
                TextButton(onClick = { showSecret = !showSecret }) {
                    Text(
                        if (showSecret) "Sembunyikan secret" else "Lihat secret",
                        fontSize = 10.sp,
                        color = CMute,
                    )
                }
                Field("Comment / label", comment, { comment = it }, password = false)
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = { saveKey() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CCyan),
                    ) { Text("Simpan", fontSize = 11.sp, color = Color.Black) }
                    if (BinanceState.configured) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) {
                            Text("Batal", fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Disimpan terenkripsi AES/GCM di Android Keystore. Tidak dikirim ke VPS.",
                    fontSize = 9.sp,
                    color = CMute,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ------------------------------------------------------------ koneksi
        CardBox {
            SectionTitle("KONEKSI")
            ToggleRow(
                title = "API Binance",
                subtitle = if (BinanceState.connected) "Tersambung · update 5 detik" else "Terputus",
                checked = BinanceState.apiEnabled,
                onCheckedChange = { setApi(it) },
            )
            ToggleRow(
                title = "Automation",
                subtitle = if (BinanceState.autoEnabled) "Berjalan" else "Mati (manual tetap bisa)",
                checked = BinanceState.autoEnabled,
                onCheckedChange = { setAuto(it) },
                enabled = BinanceState.apiEnabled && !BinanceState.busy,
            )
            if (!ServiceState.enabled) {
                Text(
                    "Layanan latar belakang mati. Nyalakan ON di bar atas tab Home.",
                    fontSize = 9.sp,
                    color = CAmber,
                )
            }
            BinanceState.lastError?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, fontSize = 9.sp, color = CRed, modifier = Modifier.weight(1f))
                    if (BinanceState.blockedIp != null) {
                        TextButton(onClick = {
                            val ip = BinanceState.blockedIp ?: return@TextButton
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("IP Binance", ip))
                            notice = "IP $ip disalin — tempel di whitelist API Binance"
                        }) { Text("Salin IP", fontSize = 9.sp, color = CCyan) }
                    }
                }
            }
            // Selalu tersedia: ambil IP publik perangkat kapan pun (tanpa menunggu error).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Whitelist API Binance butuh IP publik HP ini.",
                    fontSize = 9.sp,
                    color = CMute,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val engine = AutoEngine.instance
                        if (engine == null) {
                            notice = "Layanan Binance belum siap"
                        } else {
                            engine.checkPublicIp()
                        }
                    },
                ) {
                    Text(
                        if (BinanceState.ipLoading) "Mengambil…" else "Cek IP saya",
                        fontSize = 9.sp,
                        color = CCyan,
                    )
                }
            }
            // Kartu IP: muncul saat Binance menolak API key karena IP belum terdaftar.
            // Binance menyertakan IP yang ia lihat di pesan error (-2015), jadi bisa
            // disalin sekali tekan lalu ditempel di pengaturan API key Binance.
            BinanceState.blockedIp?.let { ip ->
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color(0xFF2A1F0A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "IP ini belum terdaftar di API Binance",
                            color = CAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Binance menolak API key dari IP di bawah. Salin, lalu tempel di " +
                                "Binance → API Management → Edit restrictions → Restrict access to trusted IPs only.",
                            color = CMute,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ip,
                                color = CText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("IP Binance", ip))
                                    notice = "IP $ip disalin ke clipboard"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CCyan),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 12.dp, vertical = 4.dp,
                                ),
                            ) { Text("Salin IP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // --------------------------------------------------------------- saldo
        CardBox {
            SectionTitle("SALDO FUTURES")
            Text(
                "$ ${money(BinanceState.walletBalance)}",
                color = CText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Row {
                Stat("Available", money(BinanceState.availableBalance), CMute)
                Spacer(Modifier.width(16.dp))
                Stat(
                    "Unrealized",
                    (if (BinanceState.unrealizedPnl >= 0) "+" else "") + money(BinanceState.unrealizedPnl),
                    if (BinanceState.unrealizedPnl >= 0) CGreen else CRed,
                )
            }
            HorizontalDivider(color = CLine, modifier = Modifier.padding(vertical = 8.dp))
            val pos = BinanceState.position
            if (pos == null) {
                Text("Tidak ada posisi terbuka", color = CMute, fontSize = 10.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (pos.isLong) "LONG" else "SHORT",
                        color = if (pos.isLong) CGreen else CRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${pos.leverage}x · ${qty(pos.absAmount)} SOL", color = CText, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        (if (pos.unrealizedPnl >= 0) "+" else "") + money(pos.unrealizedPnl) + " USDT",
                        color = if (pos.unrealizedPnl >= 0) CGreen else CRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "entry ${price(pos.entryPrice)} · mark ${price(pos.markPrice)}",
                    color = CMute,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val engine = AutoEngine.instance
                        if (engine == null) notice = "Layanan belum siap" else {
                            notice = "Menutup posisi…"
                            engine.manualClose { err -> main.post { notice = err ?: "Posisi ditutup" } }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Tutup posisi (market)", fontSize = 11.sp, color = CRed)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // --------------------------------------------------------------- manual
        CardBox {
            SectionTitle("TRADING MANUAL · MARKET")
            if (BinanceState.autoEnabled) {
                Text(
                    "Auto sedang ON. Matikan Auto untuk trading manual.",
                    color = CAmber,
                    fontSize = 10.sp,
                )
            } else if (BinanceState.position != null) {
                Text(
                    "Tutup posisi yang ada dulu sebelum entry baru.",
                    color = CAmber,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Field("Margin (USDT)", margin, { margin = it }, password = false, number = true)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Leverage", color = CMute, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${lev.toInt()}x", color = CCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = lev,
                onValueChange = { lev = it },
                valueRange = 1f..100f,
                steps = 98,
            )
            Row {
                Text("1x", color = CMute, fontSize = 8.sp)
                Spacer(Modifier.weight(1f))
                Text("100x", color = CMute, fontSize = 8.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Field("TP ROI %", tpInput, { tpInput = it }, password = false, number = true)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Field("SL ROI %", slInput, { slInput = it }, password = false, number = true)
                }
            }
            Text(
                "TP/SL dihitung dari ROI × leverage, dipasang sebagai closePosition di exchange.",
                fontSize = 9.sp,
                color = CMute,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { submitOrder(1) },
                    enabled = !BinanceState.autoEnabled && BinanceState.position == null && BinanceState.apiEnabled && !BinanceState.busy,
                    modifier = Modifier.weight(1f).height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CGreen),
                ) {
                    Text("LONG / BUY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { submitOrder(-1) },
                    enabled = !BinanceState.autoEnabled && BinanceState.position == null && BinanceState.apiEnabled && !BinanceState.busy,
                    modifier = Modifier.weight(1f).height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CRed),
                ) {
                    Text("SHORT / SELL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ----------------------------------------------------------------- auto
        CardBox {
            SectionTitle("AUTOMATION")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    phaseLabel(BinanceState.autoPhase),
                    color = if (BinanceState.autoEnabled) CCyan else CMute,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${BinanceState.modelsResponded}/${BinanceState.modelCount} respon · " +
                        "${BinanceState.modelsAligned} selaras",
                    color = CMute,
                    fontSize = 9.sp,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(BinanceState.autoDetail, color = CMute, fontSize = 10.sp)
            if (BinanceState.autoEnabled) {
                Spacer(Modifier.height(2.dp))
                if (BinanceState.autoSchemes == 0) {
                    Text(
                        "Belum ada skema entry aktif — Auto tidak akan entry sampai Anda membuat skema.",
                        color = CAmber,
                        fontSize = 9.sp,
                    )
                } else if (BinanceState.autoBlock.isNotBlank()) {
                    Text(
                        "Entry ditahan: ${BinanceState.autoBlock}",
                        color = CMute,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Leverage ${BinanceState.leverage}x · patokan app +${BinanceState.tpLevel.toInt()}% / " +
                    "-${BinanceState.slLevel.toInt()}% · SL keras exchange -${BinanceState.hardSlRoi.toInt()}%",
                color = CText,
                fontSize = 10.sp,
            )
            if (BinanceState.entryPrice > 0 && BinanceState.direction != 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "entry ${price(BinanceState.entryPrice)} · ROI ${BinanceState.roiPct.toInt()}%",
                    color = if (BinanceState.roiPct >= 0) CGreen else CRed,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            val cfg = remember(cfgVersion) { BinanceStore.autoConfig(ctx) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skema Auto", color = CText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "window ${cfg.entryWindowMin}m · lev ${cfg.leverageLadder.joinToString("→")} · " +
                            "TP ${cfg.tpLadder.joinToString("→") { it.toInt().toString() }}%" +
                            (if (cfg.tpExtend) "+" else "") + " · " +
                            "SL ${cfg.slLadder.joinToString("→") { it.toInt().toString() }}% · " +
                            "skema ${cfg.entrySchemes.count { it.enabled }}/${cfg.entrySchemes.size}",
                        color = CMute,
                        fontSize = 8.sp,
                        lineHeight = 11.sp,
                    )
                }
                TextButton(onClick = { showConfig = true }) {
                    Text("Atur", fontSize = 10.sp, color = CCyan)
                }
            }
            Spacer(Modifier.height(8.dp))
            var sizing by remember { mutableStateOf(BinanceStore.sizingOverride(ctx)) }
            OutlinedTextField(
                value = sizing,
                onValueChange = { sizing = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 12.sp, color = CText),
                placeholder = {
                    Text("Entry otomatis % saldo (kosong = 50/25/100)", fontSize = 10.sp, color = CMute)
                },
                label = { Text("Override ukuran entry (%)", fontSize = 10.sp) },
            )
            Row {
                TextButton(onClick = {
                    BinanceStore.setSizingOverride(ctx, sizing)
                    notice = if (sizing.isBlank()) "Override dihapus (pakai default)" else "Override disimpan"
                }) { Text("Simpan ukuran", fontSize = 10.sp, color = CCyan) }
                TextButton(onClick = {
                    sizing = ""
                    BinanceStore.setSizingOverride(ctx, "")
                }) { Text("Kosongkan", fontSize = 10.sp, color = CMute) }
            }
            Text(
                "Default: ≥ $55 → 25% · ≥ $27,4 → 50% · di bawah itu 100%.",
                fontSize = 9.sp,
                color = CMute,
            )
            HorizontalDivider(color = CLine, modifier = Modifier.padding(vertical = 8.dp))
            Text("LOG", fontSize = 9.sp, color = CMute, fontWeight = FontWeight.SemiBold)
            if (BinanceState.autoLog.isEmpty()) {
                Text("Belum ada kejadian.", fontSize = 9.sp, color = CMute)
            } else {
                BinanceState.autoLog.take(14).forEach {
                    Text(it, fontSize = 9.sp, color = CText, lineHeight = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Semua angka di atas diatur di tombol \"Atur skema\": window & carry, minimal model " +
                    "menjawab/selaras, syarat profit per model (≥48% / ≤-35%), gap ROI, reversal, " +
                    "tangga leverage, tangga TP, dan tangga SL. Nilai terakhir tangga SL = SL keras di " +
                    "exchange. Aksi saat gagal juga bisa dipilih (carry / reset / cooldown / hold).",
                fontSize = 9.sp,
                color = CMute,
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showConfig) {
        val current = BinanceStore.autoConfig(ctx)
        AutoConfigDialog(
            initial = current,
            onDismiss = { showConfig = false },
            onSave = { updated ->
                BinanceStore.setAutoConfig(ctx, updated)
                cfgVersion++
                showConfig = false
                notice = "Skema Auto disimpan — berlaku di loop berikutnya"
                AutoEngine.instance?.onConfigChanged()
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus API key?") },
            text = { Text("API key dan secret akan dihapus dari aplikasi, Auto dimatikan.") },
            confirmButton = {
                TextButton(onClick = {
                    BinanceStore.clearCredentials(ctx)
                    BinanceState.configured = false
                    BinanceState.apiEnabled = false
                    BinanceState.autoEnabled = false
                    BinanceState.comment = ""
                    BinanceState.resetConnection()
                    apiKey = ""
                    secret = ""
                    comment = ""
                    editing = true
                    confirmDelete = false
                    AutoEngine.instance?.onAutoDisabled()
                    notice = "API key dihapus"
                }) { Text("Hapus", color = CRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = CBar,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = CMute,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CText, fontSize = 12.sp)
            Text(subtitle, color = CMute, fontSize = 9.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontSize = 12.sp, color = CText),
        label = { Text(label, fontSize = 10.sp) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text,
        ),
    )
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(label, color = CMute, fontSize = 9.sp)
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RowScopeSpacer() {
    Spacer(Modifier.height(0.dp))
}

private fun phaseLabel(phase: String): String = when (phase) {
    "IDLE" -> "Mencari pola"
    "BATCH" -> "Mengumpulkan sinyal"
    "HOLDING" -> "Posisi terbuka"
    "RECHECK" -> "Cek ulang model"
    else -> "Auto off"
}

private fun maskKey(key: String): String = when {
    key.length <= 10 -> key
    else -> key.take(6) + "…" + key.takeLast(4)
}

private fun money(v: Double): String = String.format(Locale.US, "%,.2f", v)
private fun price(v: Double): String = String.format(Locale.US, "%.4f", v)
private fun qty(v: Double): String = String.format(Locale.US, "%.3f", v)
