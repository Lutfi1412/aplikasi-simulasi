package com.simulasi.trading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simulasi.trading.data.AutoConfig
import java.util.Locale

/**
 * Form "Skema Auto" — semua angka yang dipakai engine bisa diubah di sini:
 * waktu window, ambang izin 4/6, syarat profit, gap ROI, reversal, tangga leverage,
 * tangga TP, tangga SL, dan aksi saat skema gagal.
 *
 * Perubahan disimpan lewat [onSave]; engine membacanya tiap loop (tanpa restart).
 */
@Composable
fun AutoConfigDialog(
    initial: AutoConfig,
    onDismiss: () -> Unit,
    onSave: (AutoConfig) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Color(0xFF0B0D10),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .heightIn(max = 720.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Skema Auto",
                            color = Color(0xFFE5E7EB),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Semua angka bisa diubah · berlaku langsung",
                            color = Color(0xFF8B949E),
                            fontSize = 9.sp,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Tutup", fontSize = 11.sp, color = Color(0xFF8B949E))
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("PRESET", color = Color(0xFF8B949E), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PresetChip("Standar", draft == AutoConfig.STANDARD.sanitized()) {
                        draft = AutoConfig.STANDARD
                    }
                    PresetChip("Konservatif") { draft = AutoConfig.CONSERVATIVE }
                    PresetChip("Agresif") { draft = AutoConfig.AGGRESSIVE }
                    PresetChip("Reset") { draft = AutoConfig.STANDARD }
                }

                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ---------------------------------------------------------- waktu
                    GroupTitle("WAKTU")
                    IntRow(
                        "Window entry", draft.entryWindowMin, 5, 240, 1, "menit",
                        "Batas maksimal menunggu semua model bersinyal.",
                    ) { draft = draft.copy(entryWindowMin = it) }
                    IntRow(
                        "Carry over", draft.carryMin, 0, 240, 1, "menit",
                        "Sinyal di menit terakhir window dibawa ke window berikutnya.",
                    ) { draft = draft.copy(carryMin = it) }
                    IntRow(
                        "Cek recheck", draft.recheckMin, 5, 240, 1, "menit",
                        "Batas waktu model menjawab saat patokan tersentuh.",
                    ) { draft = draft.copy(recheckMin = it) }

                    // ---------------------------------------------------------- izin
                    GroupTitle("IZIN ENTRY / LANJUT")
                    CheckRow(
                        "Wajib semua model menjawab",
                        "Kalau dimatikan, cukup N model (lihat di bawah).",
                        draft.requireAllModels,
                    ) { draft = draft.copy(requireAllModels = it) }
                    if (!draft.requireAllModels) {
                        IntRow(
                            "Minimal model menjawab", draft.minResponded, 1, 6, 1, "model",
                            "Dipakai saat 'wajib semua model' dimatikan.",
                        ) { draft = draft.copy(minResponded = it) }
                    }
                    IntRow(
                        "Minimal model selaras", draft.minAgree, 1, 6, 1, "model",
                        "Contoh 4 = skema 4/6. Naikkan ke 5 untuk 5/6.",
                    ) { draft = draft.copy(minAgree = it) }

                    // ---------------------------------------------------------- profit
                    GroupTitle("SYARAT PROFIT (ROI BOT PER MODEL)")
                    CheckRow(
                        "Aktifkan syarat profit",
                        "Kalau mati, hanya jumlah selaras yang dinilai.",
                        draft.profitRuleEnabled,
                    ) { draft = draft.copy(profitRuleEnabled = it) }
                    if (draft.profitRuleEnabled) {
                        DecRow(
                            "Model selaras minimal ROI ≥", draft.alignedMinRoi, -100.0, 1000.0, 5.0, "%",
                            "Ambang untung untuk model yang searah.",
                        ) { draft = draft.copy(alignedMinRoi = it) }
                        IntRow(
                            "Jumlah model yang wajib ≥ ambang", draft.alignedMinCount, 1, 6, 1, "model",
                            "Contoh 2 = minimal 2 model selaras harus ≥ ambang di atas.",
                        ) { draft = draft.copy(alignedMinCount = it) }
                        DecRow(
                            "Model tidak selaras wajib ROI ≤", draft.misalignedMaxRoi, -100.0, 500.0, 5.0, "%",
                            "Contoh -35 = model lawan arah harus rugi minimal 35%.",
                        ) { draft = draft.copy(misalignedMaxRoi = it) }
                        CheckRow(
                            "Blokir kalau model yang belum bersinyal untung besar",
                            "Kasus 5/6: yang belum jawab sudah untung, entry ditahan.",
                            draft.silentProfitBlockEnabled,
                        ) { draft = draft.copy(silentProfitBlockEnabled = it) }
                        if (draft.silentProfitBlockEnabled) {
                            DecRow(
                                "Batas ROI model belum bersinyal", draft.silentProfitMax, -100.0, 1000.0, 5.0, "%",
                                "Kalau ROI-nya di atas ini, entry dibatalkan.",
                            ) { draft = draft.copy(silentProfitMax = it) }
                        }
                    }

                    // ---------------------------------------------------------- gap
                    GroupTitle("GAP ROI 1 vs SISANYA")
                    CheckRow(
                        "Aktifkan aturan gap",
                        "Model ROI tertinggi beda arah dengan jarak besar → tahan entry.",
                        draft.gapRuleEnabled,
                    ) { draft = draft.copy(gapRuleEnabled = it) }
                    if (draft.gapRuleEnabled) {
                        DecRow(
                            "Besar gap", draft.gapPct, 0.0, 500.0, 1.0, "poin",
                            "Contoh 20 = selisih ROI peringkat 1 dan 2 minimal 20 poin.",
                        ) { draft = draft.copy(gapPct = it) }
                        CheckRow(
                            "Hanya berlaku kalau ROI teratas positif",
                            "Kalau dimatikan, gap berlaku walau model teratas rugi.",
                            draft.gapOnlyIfTopPositive,
                        ) { draft = draft.copy(gapOnlyIfTopPositive = it) }
                    }

                    // ---------------------------------------------------------- reversal
                    GroupTitle("BERBALIK ARAH (REVERSAL)")
                    CheckRow(
                        "Aktifkan reversal",
                        "Saat cek gagal dan banyak model berbalik arah: tutup posisi, buka arah baru.",
                        draft.reversalEnabled,
                    ) { draft = draft.copy(reversalEnabled = it) }
                    if (draft.reversalEnabled) {
                        IntRow(
                            "Minimal model berbalik arah", draft.reversalMin, 1, 6, 1, "model",
                            "Contoh 4 = 4 dari 6 lawan arah posisi.",
                        ) { draft = draft.copy(reversalMin = it) }
                    }

                    // ---------------------------------------------------------- ladder
                    GroupTitle("TANGGA LEVERAGE ENTRY")
                    LadderRow(
                        label = "Leverage (x), urut naik",
                        value = draft.leverageLadder.joinToString(", "),
                        hint = "30, 35, 40 — entry pakai nilai pertama, naik setiap lolos cek.",
                        onValue = {
                            draft = draft.copy(leverageLadder = AutoConfig.parseLadderInts(it))
                        },
                    )
                    IntRow(
                        "Kenaikan leverage kalau tangga habis", draft.leverageStep, 1, 50, 1, "x",
                        "Dipakai setelah tangga terakhir.",
                    ) { draft = draft.copy(leverageStep = it) }
                    IntRow(
                        "Leverage maksimal", draft.maxLeverage, 1, 125, 1, "x",
                        "Batas atas kenaikan.",
                    ) { draft = draft.copy(maxLeverage = it) }

                    GroupTitle("TANGGA TP (ROI %)")
                    LadderRow(
                        label = "Level TP, urut naik",
                        value = draft.tpLadder.joinToString(", "),
                        hint = "22, 34, 50, 65 — tiap level yang kena memicu cek model.",
                        onValue = { draft = draft.copy(tpLadder = AutoConfig.parseLadderDoubles(it)) },
                    )
                    DecRow(
                        "Kenaikan TP kalau tangga habis", draft.tpStep, 1.0, 500.0, 5.0, "%",
                        "Dipakai setelah level TP terakhir.",
                    ) { draft = draft.copy(tpStep = it) }

                    GroupTitle("TANGGA SL (ROI %)")
                    LadderRow(
                        label = "Level SL, urut naik",
                        value = draft.slLadder.joinToString(", "),
                        hint = "22, 31, 40 — nilai TERAKHIR dipasang sebagai SL keras di Binance.",
                        onValue = { draft = draft.copy(slLadder = AutoConfig.parseLadderDoubles(it)) },
                    )
                    Text(
                        "SL keras exchange sekarang: -${draft.hardSlRoi.toInt()}% ROI",
                        color = Color(0xFFF59E0B),
                        fontSize = 9.sp,
                    )

                    // ---------------------------------------------------------- aksi
                    GroupTitle("AKSI SAAT SKEMA GAGAL")
                    ChoiceRow(
                        "Entry gagal",
                        listOf("CARRY", "RESET", "COOLDOWN"),
                        draft.entryFailAction,
                        mapOf(
                            "CARRY" to "Bawa sinyal carry",
                            "RESET" to "Netral semua",
                            "COOLDOWN" to "Netral + jeda",
                        ),
                    ) { draft = draft.copy(entryFailAction = it) }
                    if (draft.entryFailAction.equals("COOLDOWN", true)) {
                        IntRow(
                            "Lama jeda", draft.entryFailCooldownMin, 0, 240, 5, "menit",
                            "Tidak boleh entry selama jeda ini.",
                        ) { draft = draft.copy(entryFailCooldownMin = it) }
                    }
                    ChoiceRow(
                        "Recheck gagal",
                        listOf("OUT", "HOLD"),
                        draft.recheckFailAction,
                        mapOf("OUT" to "Tutup posisi", "HOLD" to "Tahan & cek lagi"),
                    ) { draft = draft.copy(recheckFailAction = it) }

                    Spacer(Modifier.height(10.dp))
                    PreviewBox(draft)
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedButton(
                        onClick = { draft = AutoConfig.STANDARD },
                        modifier = Modifier.weight(1f),
                    ) { Text("Default", fontSize = 11.sp, color = Color(0xFF8B949E)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(draft.sanitized()) },
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    ) {
                        Text(
                            "Simpan skema",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- parts

@Composable
private fun GroupTitle(text: String) {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = Color(0xFF232A33))
    Spacer(Modifier.height(8.dp))
    Text(text, color = Color(0xFF22D3EE), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun PresetChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp, vertical = 2.dp,
        ),
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = if (selected) Color(0xFF22C55E) else Color(0xFFE5E7EB),
        )
    }
}

/**
 * Baris angka bulat: label + tombol −/+ + input teks.
 */
@Composable
private fun IntRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    suffix: String,
    hint: String? = null,
    onChange: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.trim().toIntOrNull() != value) text = value.toString()
    }
    NumberShell(label, hint) {
        StepButton("−") { onChange((value - step).coerceIn(min, max)) }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                raw.trim().toIntOrNull()?.let { onChange(it.coerceIn(min, max)) }
            },
            singleLine = true,
            modifier = Modifier.width(78.dp),
            textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFFE5E7EB)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = { Text(suffix, fontSize = 8.sp, color = Color(0xFF8B949E)) },
        )
        StepButton("+") { onChange((value + step).coerceIn(min, max)) }
    }
}

/** Baris angka desimal (boleh negatif, mis. −35). */
@Composable
private fun DecRow(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    suffix: String,
    hint: String? = null,
    onChange: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(fmtDec(value)) }
    LaunchedEffect(value) {
        if (text.trim().replace(',', '.').toDoubleOrNull() != value) text = fmtDec(value)
    }
    NumberShell(label, hint) {
        StepButton("−") { onChange((value - step).coerceIn(min, max)) }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                raw.trim().replace(',', '.').toDoubleOrNull()?.let { onChange(it.coerceIn(min, max)) }
            },
            singleLine = true,
            modifier = Modifier.width(78.dp),
            textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFFE5E7EB)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            suffix = { Text(suffix, fontSize = 8.sp, color = Color(0xFF8B949E)) },
        )
        StepButton("+") { onChange((value + step).coerceIn(min, max)) }
    }
}

@Composable
private fun NumberShell(
    label: String,
    hint: String?,
    controls: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFE5E7EB), fontSize = 11.sp, modifier = Modifier.weight(1f))
            controls()
        }
        if (hint != null) {
            Text(hint, color = Color(0xFF8B949E), fontSize = 8.sp, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
        Text(text, fontSize = 14.sp, color = Color(0xFF22D3EE))
    }
}

@Composable
private fun CheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFE5E7EB), fontSize = 11.sp)
            Text(subtitle, color = Color(0xFF8B949E), fontSize = 8.sp, lineHeight = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LadderRow(
    label: String,
    value: String,
    hint: String,
    onValue: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (text != value) text = value }
    Column(Modifier.padding(vertical = 3.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValue(it)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFFE5E7EB)),
            label = { Text(label, fontSize = 9.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(hint, color = Color(0xFF8B949E), fontSize = 8.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    desc: Map<String, String>,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = Color(0xFFE5E7EB), fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { opt ->
                val active = selected.equals(opt, ignoreCase = true)
                OutlinedButton(
                    onClick = { onSelect(opt) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 4.dp,
                    ),
                ) {
                    Text(
                        (desc[opt] ?: opt),
                        fontSize = 9.sp,
                        color = if (active) Color(0xFF22C55E) else Color(0xFF8B949E),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewBox(cfg: AutoConfig) {
    val safe = cfg.sanitized()
    Surface(color = Color(0xFF11151A), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text("RINGKASAN", color = Color(0xFF8B949E), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                "Window ${safe.entryWindowMin}m · carry ${safe.carryMin}m · recheck ${safe.recheckMin}m · " +
                    "selaras ≥${safe.minAgree} · " +
                    (if (safe.requireAllModels) "6/6" else "≥${safe.minResponded}") + " menjawab",
                color = Color(0xFFE5E7EB), fontSize = 9.sp, lineHeight = 12.sp,
            )
            Text(
                "Profit: " + if (safe.profitRuleEnabled) {
                    "${safe.alignedMinCount} selaras ≥${safe.alignedMinRoi.toInt()}% · " +
                        "lawan arah ≤${safe.misalignedMaxRoi.toInt()}%"
                } else "nonaktif",
                color = Color(0xFFE5E7EB), fontSize = 9.sp, lineHeight = 12.sp,
            )
            Text(
                "Gap: " + if (safe.gapRuleEnabled) "≥${safe.gapPct.toInt()} poin" else "nonaktif" +
                    " · reversal ≥${safe.reversalMin} model",
                color = Color(0xFFE5E7EB), fontSize = 9.sp, lineHeight = 12.sp,
            )
            Text(
                "Leverage ${safe.leverageLadder.joinToString("→")} · " +
                    "TP ${safe.tpLadder.joinToString("→") { it.toInt().toString() }}% · " +
                    "SL ${safe.slLadder.joinToString("→") { it.toInt().toString() }}% · " +
                    "SL keras -${safe.hardSlRoi.toInt()}%",
                color = Color(0xFF22D3EE), fontSize = 9.sp, lineHeight = 12.sp,
            )
            Text(
                "Gagal entry: ${safe.entryFailAction} · gagal recheck: ${safe.recheckFailAction}",
                color = Color(0xFF8B949E), fontSize = 9.sp,
            )
        }
    }
}

/** Angka desimal tanpa nol ekor: 48.0 → "48", -35.5 → "-35.5". */
private fun fmtDec(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
