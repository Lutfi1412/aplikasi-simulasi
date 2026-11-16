package com.simulasi.trading.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.simulasi.trading.binance.BinancePosition

/** Status Binance yang dibaca Compose (diisi oleh AutoEngine di layanan latar belakang). */
object BinanceState {
    var configured by mutableStateOf(false)
    var comment by mutableStateOf("")
    var apiEnabled by mutableStateOf(false)
    var autoEnabled by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var busy by mutableStateOf(false)

    var walletBalance by mutableStateOf(0.0)
    var availableBalance by mutableStateOf(0.0)
    var marginBalance by mutableStateOf(0.0)
    var unrealizedPnl by mutableStateOf(0.0)
    var position by mutableStateOf<BinancePosition?>(null)

    var lastError by mutableStateOf<String?>(null)
    /** IP publik yang dilaporkan Binance saat API key ditolak (bisa disalin ke whitelist). */
    var blockedIp by mutableStateOf<String?>(null)
    /** true saat aplikasi sedang mengambil IP publik sendiri ("Cek IP saya"). */
    var ipLoading by mutableStateOf(false)
    var lastUpdate by mutableStateOf(0L)

    var autoPhase by mutableStateOf("OFF")
    var autoDetail by mutableStateOf("API Binance mati")
    var autoLog by mutableStateOf<List<String>>(emptyList())
    /** Jumlah skema entry yang aktif (0 = Auto tidak akan entry). */
    var autoSchemes by mutableStateOf(0)
    /** Alasan terakhir entry ditolak — supaya tidak perlu menebak dari log. */
    var autoBlock by mutableStateOf("")
    var modelsResponded by mutableStateOf(0)
    var modelsAligned by mutableStateOf(0)
    var modelCount by mutableStateOf(6)

    var leverage by mutableStateOf(30)
    /** level patokan aplikasi yang sedang dipantau (ROI %) */
    var tpLevel by mutableStateOf(22.0)
    var slLevel by mutableStateOf(22.0)
    /** SL keras di exchange (ROI %), selalu -40 */
    var hardSlRoi by mutableStateOf(40.0)
    var direction by mutableStateOf(0)
    var entryPrice by mutableStateOf(0.0)
    var roiPct by mutableStateOf(0.0)

    fun resetConnection() {
        connected = false
        walletBalance = 0.0
        availableBalance = 0.0
        marginBalance = 0.0
        unrealizedPnl = 0.0
        position = null
        autoPhase = "OFF"
        autoDetail = "API Binance mati"
        blockedIp = null
        ipLoading = false
        modelsResponded = 0
        modelsAligned = 0
        direction = 0
        entryPrice = 0.0
        roiPct = 0.0
        tpLevel = 22.0
        slLevel = 22.0
    }
}
