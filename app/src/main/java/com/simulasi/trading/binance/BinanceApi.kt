package com.simulasi.trading.binance

import com.simulasi.trading.data.BinanceCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.floor

class BinanceException(val code: Int, message: String) : Exception(message)

data class BinancePosition(
    val symbol: String,
    val amount: Double,
    val entryPrice: Double,
    val markPrice: Double,
    val unrealizedPnl: Double,
    val leverage: Int,
) {
    val isLong: Boolean get() = amount > 0
    val absAmount: Double get() = abs(amount)
}

data class BinanceAccount(
    val walletBalance: Double,
    val availableBalance: Double,
    val marginBalance: Double,
    val unrealizedPnl: Double,
    val positions: List<BinancePosition>,
)

data class SymbolRules(
    val stepSize: Double,
    val qtyDecimals: Int,
    val tickSize: Double,
    val priceDecimals: Int,
    val minNotional: Double,
)

data class RealizedPnl(val pnl: Double, val ts: Long)

/**
 * Klien REST Binance USDⓈ-M Futures. Hanya butuh API key + secret (HMAC-SHA256).
 * Tidak ada kunci yang dikirim ke server simulasi/VPS.
 */
class BinanceApi(private val credentials: BinanceCredentials) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var timeOffset = 0L
    @Volatile private var cachedRules: SymbolRules? = null
    @Volatile var hedgeMode: Boolean = false
        private set

    suspend fun syncTime() {
        val j = json("GET", "/fapi/v1/time", emptyMap(), signed = false)
        val server = j.optLong("serverTime", 0L)
        if (server > 0) timeOffset = server - System.currentTimeMillis()
    }

    suspend fun markPrice(symbol: String): Double {
        val j = json(
            "GET",
            "/fapi/v1/ticker/price",
            linkedMapOf("symbol" to symbol),
            signed = false,
        )
        return j.optString("price").toDoubleOrNull() ?: 0.0
    }

    suspend fun account(): BinanceAccount {
        val j = json("GET", "/fapi/v2/account", emptyMap(), signed = true)
        val positions = ArrayList<BinancePosition>()
        val arr = j.optJSONArray("positions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val amt = p.optString("positionAmt").toDoubleOrNull() ?: 0.0
            if (abs(amt) <= 0.0) continue
            positions += BinancePosition(
                symbol = p.optString("symbol"),
                amount = amt,
                entryPrice = p.optString("entryPrice").toDoubleOrNull() ?: 0.0,
                markPrice = p.optString("markPrice").toDoubleOrNull() ?: 0.0,
                unrealizedPnl = p.optString("unRealizedProfit").toDoubleOrNull() ?: 0.0,
                leverage = p.optString("leverage").toIntOrNull() ?: 0,
            )
        }
        return BinanceAccount(
            walletBalance = j.optString("totalWalletBalance").toDoubleOrNull() ?: 0.0,
            availableBalance = j.optString("availableBalance").toDoubleOrNull() ?: 0.0,
            marginBalance = j.optString("totalMarginBalance").toDoubleOrNull() ?: 0.0,
            unrealizedPnl = j.optString("totalUnrealizedProfit").toDoubleOrNull() ?: 0.0,
            positions = positions,
        )
    }

    suspend fun rules(symbol: String): SymbolRules {
        cachedRules?.let { return it }
        val j = json("GET", "/fapi/v1/exchangeInfo", emptyMap(), signed = false)
        val symbols = j.optJSONArray("symbols") ?: JSONArray()
        for (i in 0 until symbols.length()) {
            val s = symbols.optJSONObject(i) ?: continue
            if (s.optString("symbol") != symbol) continue
            var step = 0.001
            var tick = 0.01
            var minNotional = 5.0
            val filters = s.optJSONArray("filters") ?: JSONArray()
            for (k in 0 until filters.length()) {
                val f = filters.optJSONObject(k) ?: continue
                when (f.optString("filterType")) {
                    "LOT_SIZE", "MARKET_LOT_SIZE" -> {
                        val v = f.optString("stepSize").toDoubleOrNull()
                        if (v != null && v > 0) step = v
                    }
                    "PRICE_FILTER" -> {
                        val v = f.optString("tickSize").toDoubleOrNull()
                        if (v != null && v > 0) tick = v
                    }
                    "MIN_NOTIONAL" -> {
                        val v = f.optString("notional").toDoubleOrNull()
                        if (v != null && v > 0) minNotional = v
                    }
                }
            }
            val rules = SymbolRules(
                stepSize = step,
                qtyDecimals = decimalsOf(step),
                tickSize = tick,
                priceDecimals = decimalsOf(tick),
                minNotional = minNotional,
            )
            cachedRules = rules
            return rules
        }
        throw BinanceException(-1, "symbol $symbol tidak ditemukan di exchangeInfo")
    }

    suspend fun refreshHedgeMode(): Boolean {
        hedgeMode = json("GET", "/fapi/v1/positionSide/dual", emptyMap(), signed = true)
            .optBoolean("dualSidePosition", false)
        return hedgeMode
    }

    suspend fun setLeverage(symbol: String, leverage: Int) {
        json(
            "POST",
            "/fapi/v1/leverage",
            linkedMapOf("symbol" to symbol, "leverage" to leverage.toString()),
            signed = true,
        )
    }

    suspend fun openOrders(symbol: String): List<JSONObject> {
        val arr = array("GET", "/fapi/v1/openOrders", linkedMapOf("symbol" to symbol), signed = true)
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += it }
        return out
    }

    suspend fun cancelAll(symbol: String) {
        try {
            json(
                "DELETE",
                "/fapi/v1/allOpenOrders",
                linkedMapOf("symbol" to symbol),
                signed = true,
            )
        } catch (_: BinanceException) {
        }
    }

    suspend fun marketOrder(
        symbol: String,
        side: String,
        quantity: String,
        clientId: String,
        reduceOnly: Boolean = false,
        positionSide: String? = null,
    ): JSONObject {
        val p = linkedMapOf(
            "symbol" to symbol,
            "side" to side,
            "type" to "MARKET",
            "quantity" to quantity,
            "newClientOrderId" to clientId,
            "newOrderRespType" to "RESULT",
        )
        if (reduceOnly) p["reduceOnly"] = "true"
        if (positionSide != null) p["positionSide"] = positionSide
        return json("POST", "/fapi/v1/order", p, signed = true)
    }

    suspend fun closePositionOrder(
        symbol: String,
        side: String,
        type: String,
        stopPrice: String,
        clientId: String,
        positionSide: String? = null,
    ): JSONObject {
        val p = linkedMapOf(
            "symbol" to symbol,
            "side" to side,
            "type" to type,
            "stopPrice" to stopPrice,
            "closePosition" to "true",
            "workingType" to "MARK_PRICE",
            "newClientOrderId" to clientId,
        )
        if (positionSide != null) p["positionSide"] = positionSide
        return json("POST", "/fapi/v1/order", p, signed = true)
    }

    suspend fun lastRealizedPnl(symbol: String): RealizedPnl? {
        val arr = array(
            "GET",
            "/fapi/v1/userTrades",
            linkedMapOf("symbol" to symbol, "limit" to "10"),
            signed = true,
        )
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pnl = o.optString("realizedPnl").toDoubleOrNull() ?: 0.0
            if (abs(pnl) < 0.0000001) continue
            if (best == null || o.optLong("time") > best.optLong("time")) best = o
        }
        val o = best ?: return null
        return RealizedPnl(
            pnl = o.optString("realizedPnl").toDoubleOrNull() ?: 0.0,
            ts = o.optLong("time", 0L),
        )
    }

    fun formatQty(value: Double, rules: SymbolRules): String {
        val steps = floor(value / rules.stepSize + 1e-9)
        val qty = steps * rules.stepSize
        return String.format(Locale.US, "%.${rules.qtyDecimals}f", qty)
    }

    fun formatPrice(value: Double, rules: SymbolRules): String =
        String.format(Locale.US, "%.${rules.priceDecimals}f", value)

    private suspend fun json(
        method: String,
        path: String,
        params: Map<String, String>,
        signed: Boolean,
    ): JSONObject = JSONObject(raw(method, path, params, signed))

    private suspend fun array(
        method: String,
        path: String,
        params: Map<String, String>,
        signed: Boolean,
    ): JSONArray = JSONArray(raw(method, path, params, signed))

    private suspend fun raw(
        method: String,
        path: String,
        params: Map<String, String>,
        signed: Boolean,
    ): String {
        return try {
            rawOnce(method, path, params, signed)
        } catch (e: BinanceException) {
            if (e.code == -1021) {
                syncTime()
                rawOnce(method, path, params, signed)
            } else {
                throw e
            }
        }
    }

    private suspend fun rawOnce(
        method: String,
        path: String,
        params: Map<String, String>,
        signed: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val p = LinkedHashMap(params)
        if (signed) {
            p["timestamp"] = (System.currentTimeMillis() + timeOffset).toString()
            p["recvWindow"] = "5000"
        }
        var query = p.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        if (signed) {
            query += "&signature=" + hmac(query)
        }

        val builder = Request.Builder().header("X-MBX-APIKEY", credentials.apiKey)
        when (method) {
            "GET" -> builder.url("https://fapi.binance.com$path?$query").get()
            "DELETE" -> builder.url("https://fapi.binance.com$path?$query").delete()
            "POST" -> builder
                .url("https://fapi.binance.com$path")
                .post(query.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            else -> throw IllegalArgumentException("method $method")
        }

        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val code = try {
                    JSONObject(text).optInt("code", resp.code)
                } catch (_: Exception) {
                    resp.code
                }
                val msg = try {
                    JSONObject(text).optString("msg", text)
                } catch (_: Exception) {
                    text.ifBlank { "HTTP ${resp.code}" }
                }
                throw BinanceException(code, msg)
            }
            text
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun hmac(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(credentials.secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun decimalsOf(step: Double): Int {
        val s = step.toString()
        return if (s.contains('.')) s.substringAfter('.').length else 0
    }

    companion object {
        private const val HEX = "0123456789abcdef"
    }
}
