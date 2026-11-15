package com.simulasi.trading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.simulasi.trading.data.AutoCommon
import com.simulasi.trading.data.SchemeRule
import java.util.Locale

/** Palet lokal supaya file ini tidak tergantung konstanta privat BinanceScreen. */
private val D_BG = Color(0xFF0B0D10)
private val D_BAR = Color(0xFF11151A)
private val D_LINE = Color(0xFF232A33)
private val D_MUTE = Color(0xFF8B949E)
private val D_TEXT = Color(0xFFE5E7EB)
private val D_GREEN = Color(0xFF22C55E)
private val D_CYAN = Color(0xFF22D3EE)
private val D_AMBER = Color(0xFFF59E0B)
private val D_RED = Color(0xFFEF4444)

private enum class Tab { UMUM, ENTRY, RECHECK }

/**
 * Form setelan Auto.
 *
 * Dipisah menjadi:
 *  - **Umum** — waktu (window/carry/recheck), tangga leverage, tangga TP, tangga SL,
 *    toggle peningkatan ROI, dan aksi saat skema gagal.
 *  - **Skema entry** — banyak skema, masing-masing berisi syarat (responden, selaras,
 *    profit, gap). Logika OR: satu skema cocok = ACC.
 *  - **Skema recheck** — sama, dipakai saat menilai posisi yang sedang berjalan.
 */
@Composable
fun AutoConfigDialog(
    initial: AutoCommon,
    onDismiss: () -> Unit,
    onSave: (AutoCommon) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var tab by remember { mutableStateOf(Tab.UMUM) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = D_BG,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(0.96f).heightIn(max = 740.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Setelan Auto", color = D_TEXT, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Umum + banyak skema · berlaku langsung", color = D_MUTE, fontSize = 9.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Tutup", fontSize = 11.sp, color = D_MUTE)
                    }
                }

                Spacer(Modifier.height(6.dp))
                TabRow(tab) { tab = it }

                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                ) {
                    when (tab) {
                        Tab.UMUM -> UmumTab(draft) { draft = it }
                        Tab.ENTRY -> SchemeTab(
                            title = "SKEMA ENTRY",
                            note = "Kalau ADA satu skema aktif yang cocok → entry (OR). " +
                                "Beberapa skema boleh aktif sekaligus.",
                            schemes = draft.entrySchemes,
                        ) { updated -> draft = draft.copy(entrySchemes = updated) }
                        Tab.RECHECK -> SchemeTab(
                            title = "SKEMA RECHECK",
                            note = "Dipakai saat patokan tersentuh. Kalau ada satu skema aktif cocok → " +
                                "LANJUT (posisi tetap jalan). Reversal dicek lebih dulu.",
                            schemes = draft.recheckSchemes,
                        ) { updated -> draft = draft.copy(recheckSchemes = updated) }
                    }
                    Spacer(Modifier.height(10.dp))
                    PreviewBox(draft)
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedButton(
                        onClick = { draft = AutoCommon.STANDARD },
                        modifier = Modifier.weight(1f),
                    ) { Text("Default", fontSize = 11.sp, color = D_MUTE) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { draft = AutoCommon.CONSERVATIVE },
                        modifier = Modifier.weight(1f),
                    ) { Text("Konservatif", fontSize = 10.sp, color = D_MUTE) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { draft = AutoCommon.AGGRESSIVE },
                        modifier = Modifier.weight(1f),
                    ) { Text("Agresif", fontSize = 10.sp, color = D_MUTE) }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { onSave(draft.sanitized()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = D_GREEN),
                ) {
                    Text("Simpan setelan", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun TabRow(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TabChip("Umum", selected == Tab.UMUM) { onSelect(Tab.UMUM) }
        TabChip("Skema entry", selected == Tab.ENTRY) { onSelect(Tab.ENTRY) }
        TabChip("Skema recheck", selected == Tab.RECHECK) { onSelect(Tab.RECHECK) }
    }
}

@Composable
private fun TabChip(label: String, active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) D_GREEN else D_MUTE,
        )
    }
}

// ------------------------------------------------------------------ tab umum

@Composable
private fun UmumTab(cfg: AutoCommon, onChange: (AutoCommon) -> Unit) {
    GroupTitle("WAKTU")
    IntRow(
        "Window entry", cfg.entryWindowMin, 5, 240, 1, "menit",
        "Batas maksimal menunggu model bersinyal.",
    ) { onChange(cfg.copy(entryWindowMin = it)) }
    IntRow(
        "Carry over", cfg.carryMin, 0, 240, 1, "menit",
        "Sinyal di menit terakhir window dibawa ke window berikutnya.",
    ) { onChange(cfg.copy(carryMin = it)) }
    IntRow(
        "Cek recheck", cfg.recheckMin, 5, 240, 1, "menit",
        "Batas waktu model menjawab saat patokan tersentuh.",
    ) { onChange(cfg.copy(recheckMin = it)) }

    GroupTitle("TANGGA LEVERAGE (ENTRY → NAIK TIAP LOLOS CEK)")
    LadderRow(
        label = "Leverage (x), urut naik",
        value = cfg.leverageLadder.joinToString(", "),
        hint = "Contoh 30, 35, 40 — entry pakai angka pertama.",
    ) { onChange(cfg.copy(leverageLadder = AutoCommon.parseLadderInts(it))) }
    IntRow("Kenaikan setelah tangga habis", cfg.leverageStep, 1, 50, 1, "x", null) {
        onChange(cfg.copy(leverageStep = it))
    }
    IntRow("Leverage maksimal", cfg.maxLeverage, 1, 125, 1, "x", null) {
        onChange(cfg.copy(maxLeverage = it))
    }

    GroupTitle("TANGGA TP — PATOKAN, BUKAN TP ORDER")
    LadderRow(
        label = "Level TP (ROI %), urut naik",
        value = cfg.tpLadder.joinToString(", "),
        hint = "Menyentuh level = cek model (posisi tetap jalan). Angka terakhir jadi TP asli " +
            "kalau peningkatan ROI dimatikan.",
    ) { onChange(cfg.copy(tpLadder = AutoCommon.parseLadderDoubles(it))) }
    CheckRow(
        "Peningkatan ROI (tambah terus)",
        "ON: setelah angka terakhir, patokan naik +step (tidak ada TP asli). " +
            "OFF: angka terakhir = TP asli, menyentuhnya langsung menutup posisi.",
        cfg.tpExtend,
    ) { onChange(cfg.copy(tpExtend = it)) }
    DecRow(
        "Kenaikan TP", cfg.tpStep, 1.0, 500.0, 5.0, "%",
        if (cfg.tpExtend) "Dipakai setelah level terakhir." else "Tidak dipakai (peningkatan OFF).",
    ) { onChange(cfg.copy(tpStep = it)) }

    GroupTitle("TANGGA SL — PATOKAN")
    LadderRow(
        label = "Level SL (ROI %), urut naik",
        value = cfg.slLadder.joinToString(", "),
        hint = "SL terdalam selalu dipasang sebagai STOP_MARKET di exchange. Angka terakhir jadi " +
            "SL asli kalau peningkatan ROI dimatikan.",
    ) { onChange(cfg.copy(slLadder = AutoCommon.parseLadderDoubles(it))) }
    CheckRow(
        "Peningkatan ROI SL (melebar terus)",
        "ON: setelah angka terakhir, batas rugi melebar +step (tidak ada SL asli selain " +
            "SL terdalam). OFF: angka terakhir = SL asli.",
        cfg.slExtend,
    ) { onChange(cfg.copy(slExtend = it)) }
    DecRow(
        "Kenaikan SL", cfg.slStep, 1.0, 100.0, 1.0, "%",
        if (cfg.slExtend) "Dipakai setelah level terakhir." else "Tidak dipakai (peningkatan OFF).",
    ) { onChange(cfg.copy(slStep = it)) }
    Text(
        "SL keras exchange sekarang: -${cfg.maxSl.toInt()}% ROI",
        color = D_AMBER,
        fontSize = 9.sp,
    )

    GroupTitle("AKSI SAAT SKEMA GAGAL")
    ChoiceRow(
        "Entry gagal",
        listOf("CARRY", "RESET", "COOLDOWN"),
        cfg.entryFailAction,
        mapOf("CARRY" to "Bawa carry", "RESET" to "Netral", "COOLDOWN" to "Netral+jeda"),
    ) { onChange(cfg.copy(entryFailAction = it)) }
    if (cfg.entryFailAction.equals("COOLDOWN", true)) {
        IntRow("Lama jeda", cfg.entryFailCooldownMin, 0, 240, 5, "menit", null) {
            onChange(cfg.copy(entryFailCooldownMin = it))
        }
    }
    ChoiceRow(
        "Recheck gagal",
        listOf("OUT", "HOLD"),
        cfg.recheckFailAction,
        mapOf("OUT" to "Tutup posisi", "HOLD" to "Tahan & cek lagi"),
    ) { onChange(cfg.copy(recheckFailAction = it)) }

    GroupTitle("BERBALIK ARAH (GLOBAL, DICEK SEBELUM SKEMA)")
    CheckRow(
        "Aktifkan reversal",
        "Kalau cukup model berbalik arah: tutup posisi, buka arah baru (level fresh).",
        cfg.reversalEnabled,
    ) { onChange(cfg.copy(reversalEnabled = it)) }
    if (cfg.reversalEnabled) {
        IntRow("Minimal model berbalik", cfg.reversalMin, 1, 6, 1, "model", null) {
            onChange(cfg.copy(reversalMin = it))
        }
    }
}

// ---------------------------------------------------------------- tab skema

@Composable
private fun SchemeTab(
    title: String,
    note: String,
    schemes: List<SchemeRule>,
    onChange: (List<SchemeRule>) -> Unit,
) {
    var editing by remember { mutableStateOf<SchemeRule?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Text(title, color = D_CYAN, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    Text(note, color = D_MUTE, fontSize = 8.sp, lineHeight = 11.sp)
    Spacer(Modifier.height(6.dp))

    schemes.forEach { scheme ->
        Surface(
            color = if (scheme.enabled) D_BAR else Color(0xFF14181D),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            scheme.name + if (scheme.enabled) "" else " (nonaktif)",
                            color = if (scheme.enabled) D_TEXT else D_MUTE,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(scheme.summary(), color = D_MUTE, fontSize = 8.sp, lineHeight = 11.sp)
                    }
                    Switch(
                        checked = scheme.enabled,
                        onCheckedChange = { on ->
                            onChange(schemes.map { if (it.id == scheme.id) it.copy(enabled = on) else it })
                        },
                    )
                }
                Row {
                    TextButton(onClick = { editing = scheme }) {
                        Text("Edit", fontSize = 9.sp, color = D_CYAN)
                    }
                    TextButton(onClick = {
                        onChange(
                            schemes.map {
                                if (it.id == scheme.id) it.copy(id = newId(it.id)) else it
                            },
                        )
                    }) { Text("Duplikat", fontSize = 9.sp, color = D_MUTE) }
                    TextButton(onClick = { confirmDelete = scheme.id }) {
                        Text("Hapus", fontSize = 9.sp, color = D_RED)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = {
            val base = schemes.firstOrNull() ?: SchemeRule.ENTRY_DEFAULT
            editing = base.copy(
                id = "s-${System.currentTimeMillis()}",
                name = "Skema ${schemes.size + 1}",
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("+ Tambah skema", fontSize = 10.sp, color = D_CYAN) }

    editing?.let { scheme ->
        SchemeEditorDialog(
            initial = scheme,
            isNew = schemes.none { it.id == scheme.id },
            onDismiss = { editing = null },
            onSave = { updated ->
                val exists = schemes.any { it.id == updated.id }
                onChange(
                    if (exists) schemes.map { if (it.id == updated.id) updated else it }
                    else schemes + updated,
                )
                editing = null
            },
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Hapus skema?") },
            text = { Text("Skema ini akan dihapus dari daftar.") },
            confirmButton = {
                TextButton(onClick = {
                    onChange(schemes.filterNot { it.id == id })
                    confirmDelete = null
                }) { Text("Hapus", color = D_RED) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Batal") }
            },
        )
    }
}

private fun newId(base: String): String = "$base-${System.currentTimeMillis().toString().takeLast(6)}"

@Composable
private fun SchemeEditorDialog(
    initial: SchemeRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (SchemeRule) -> Unit,
) {
    var rule by remember { mutableStateOf(initial) }
    var nameText by remember { mutableStateOf(initial.name) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = D_BG,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 680.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (isNew) "Skema baru" else "Edit skema",
                    color = D_TEXT, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        nameText = it
                        rule = rule.copy(name = it)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp, color = D_TEXT),
                    label = { Text("Nama skema", fontSize = 9.sp) },
                )

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    GroupTitle("RESPONDEN")
                    CheckRow(
                        "Wajib semua model menjawab",
                        "Kalau dimatikan, cukup N model.",
                        rule.requireAllModels,
                    ) { rule = rule.copy(requireAllModels = it) }
                    if (!rule.requireAllModels) {
                        IntRow("Minimal menjawab", rule.minResponded, 1, 6, 1, "model", null) {
                            rule = rule.copy(minResponded = it)
                        }
                    }
                    IntRow(
                        "Minimal selaras", rule.minAgree, 1, 6, 1, "model",
                        "4 = skema 4/6, 5 = skema 5/6.",
                    ) { rule = rule.copy(minAgree = it) }

                    GroupTitle("SYARAT PROFIT (ROI BOT PER MODEL)")
                    CheckRow(
                        "Aktifkan syarat profit",
                        "Kalau mati, hanya jumlah selaras yang dinilai.",
                        rule.profitRuleEnabled,
                    ) { rule = rule.copy(profitRuleEnabled = it) }
                    if (rule.profitRuleEnabled) {
                        DecRow(
                            "Model selaras minimal ROI ≥", rule.alignedMinRoi, -100.0, 1000.0, 5.0, "%",
                            null,
                        ) { rule = rule.copy(alignedMinRoi = it) }
                        IntRow(
                            "Jumlah model wajib ≥ ambang", rule.alignedMinCount, 1, 6, 1, "model",
                            null,
                        ) { rule = rule.copy(alignedMinCount = it) }
                        DecRow(
                            "Model tidak selaras wajib ROI ≤", rule.misalignedMaxRoi, -100.0, 500.0, 5.0, "%",
                            "Contoh -35 = harus rugi minimal 35%.",
                        ) { rule = rule.copy(misalignedMaxRoi = it) }
                    }

                    GroupTitle("MODEL BELUM BERSINYAL")
                    CheckRow(
                        "Blokir kalau ROI-nya sudah tinggi",
                        "Kasus 5/6: yang belum jawab sudah untung besar → skema batal.",
                        rule.silentProfitBlockEnabled,
                    ) { rule = rule.copy(silentProfitBlockEnabled = it) }
                    if (rule.silentProfitBlockEnabled) {
                        DecRow(
                            "Batas ROI", rule.silentProfitMax, -100.0, 1000.0, 5.0, "%",
                            "Di atas angka ini → blokir.",
                        ) { rule = rule.copy(silentProfitMax = it) }
                    }

                    GroupTitle("GAP ROI 1 vs SISANYA")
                    CheckRow(
                        "Aktifkan aturan gap",
                        "Model ROI tertinggi beda arah dengan jarak besar → skema batal.",
                        rule.gapRuleEnabled,
                    ) { rule = rule.copy(gapRuleEnabled = it) }
                    if (rule.gapRuleEnabled) {
                        DecRow(
                            "Besar gap", rule.gapPct, 0.0, 500.0, 1.0, "poin",
                            "Contoh 20 = selisih peringkat 1 dan 2 minimal 20 poin.",
                        ) { rule = rule.copy(gapPct = it) }
                        CheckRow(
                            "Hanya kalau ROI teratas positif",
                            "Kalau mati, gap berlaku walau model teratas rugi.",
                            rule.gapOnlyIfTopPositive,
                        ) { rule = rule.copy(gapOnlyIfTopPositive = it) }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Batal", fontSize = 11.sp, color = D_MUTE)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(rule.copy(name = nameText).sanitized()) },
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = D_GREEN),
                    ) {
                        Text("Simpan", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- parts

@Composable
private fun GroupTitle(text: String) {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = D_LINE)
    Spacer(Modifier.height(8.dp))
    Text(text, color = D_CYAN, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

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
            modifier = Modifier.width(76.dp),
            textStyle = TextStyle(fontSize = 11.sp, color = D_TEXT),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = { Text(suffix, fontSize = 8.sp, color = D_MUTE) },
        )
        StepButton("+") { onChange((value + step).coerceIn(min, max)) }
    }
}

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
            modifier = Modifier.width(76.dp),
            textStyle = TextStyle(fontSize = 11.sp, color = D_TEXT),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            suffix = { Text(suffix, fontSize = 8.sp, color = D_MUTE) },
        )
        StepButton("+") { onChange((value + step).coerceIn(min, max)) }
    }
}

@Composable
private fun NumberShell(label: String, hint: String?, controls: @Composable RowScope.() -> Unit) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = D_TEXT, fontSize = 11.sp, modifier = Modifier.weight(1f))
            controls()
        }
        if (hint != null) {
            Text(hint, color = D_MUTE, fontSize = 8.sp, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
    ) {
        Text(text, fontSize = 14.sp, color = D_CYAN)
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
            Text(title, color = D_TEXT, fontSize = 11.sp)
            Text(subtitle, color = D_MUTE, fontSize = 8.sp, lineHeight = 11.sp)
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
            textStyle = TextStyle(fontSize = 11.sp, color = D_TEXT),
            label = { Text(label, fontSize = 9.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(hint, color = D_MUTE, fontSize = 8.sp, lineHeight = 11.sp)
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
        Text(label, color = D_TEXT, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { opt ->
                val active = selected.equals(opt, ignoreCase = true)
                OutlinedButton(
                    onClick = { onSelect(opt) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 2.dp,
                    ),
                ) {
                    Text(
                        desc[opt] ?: opt,
                        fontSize = 9.sp,
                        color = if (active) D_GREEN else D_MUTE,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewBox(cfg: AutoCommon) {
    val safe = cfg.sanitized()
    Surface(color = D_BAR, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text("RINGKASAN", color = D_MUTE, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                "Window ${safe.entryWindowMin}m · carry ${safe.carryMin}m · recheck ${safe.recheckMin}m",
                color = D_TEXT, fontSize = 9.sp,
            )
            Text(
                "Leverage ${safe.leverageLadder.joinToString("→")} " +
                    "(+${safe.leverageStep}, maks ${safe.maxLeverage})",
                color = D_TEXT, fontSize = 9.sp,
            )
            Text(
                "TP " + safe.tpLadder.joinToString("→") { it.toInt().toString() } + "% " +
                    if (safe.tpExtend) "(+${safe.tpStep.toInt()}% terus = patokan)" else "(akhir = TP asli)",
                color = D_CYAN, fontSize = 9.sp,
            )
            Text(
                "SL " + safe.slLadder.joinToString("→") { it.toInt().toString() } + "% " +
                    if (safe.slExtend) "(+${safe.slStep.toInt()}% terus = patokan)" else "(akhir = SL asli)",
                color = D_AMBER, fontSize = 9.sp,
            )
            Text(
                "Skema entry: ${safe.entrySchemes.count { it.enabled }}/${safe.entrySchemes.size} aktif · " +
                    "recheck: ${safe.recheckSchemes.count { it.enabled }}/${safe.recheckSchemes.size} aktif",
                color = D_TEXT, fontSize = 9.sp,
            )
            Text(
                "Gagal entry: ${safe.entryFailAction} · gagal recheck: ${safe.recheckFailAction} · " +
                    "reversal ≥${safe.reversalMin} model",
                color = D_MUTE, fontSize = 9.sp,
            )
        }
    }
}

private fun fmtDec(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
