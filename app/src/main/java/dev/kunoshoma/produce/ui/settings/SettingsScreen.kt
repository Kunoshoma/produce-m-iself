package dev.kunoshoma.produce.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import dev.kunoshoma.produce.backup.BackupManager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import dev.kunoshoma.produce.ui.theme.StaminaGreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.rules.RankThresholds
import dev.kunoshoma.produce.ui.actions.NumberField
import dev.kunoshoma.produce.ui.components.ChipRow
import dev.kunoshoma.produce.ui.components.SectionTitle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onBlockedApps: () -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val accessibilityOn by vm.accessibilityEnabled.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmImport by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportBackup) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { confirmImport = it } }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("割り込み（娯楽アプリ検知）")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("割り込みを使う")
                    Text(
                        if (accessibilityOn) "アクセシビリティ：ON" else "アクセシビリティ：OFF（下のボタンから有効化）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (accessibilityOn) StaminaGreen else MaterialTheme.colorScheme.error,
                    )
                }
                Switch(checked = s.interruptEnabled, onCheckedChange = { v -> vm.update { it.copy(interruptEnabled = v) } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.weight(1f),
                ) { Text("アクセシビリティ設定") }
                FilledTonalButton(onClick = onBlockedApps, modifier = Modifier.weight(1f)) { Text("対象アプリを選ぶ") }
            }
            Text(
                "見るのは前面アプリのパッケージ名だけ。画面の内容は読まない。Pixel の「使っていないアプリの権限を削除」が ON だと切れることがあるので、このアプリは OFF にしておくと安心。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text("静かな時間（割り込まない）", Modifier.weight(1f))
                Switch(checked = s.quietHours.enabled, onCheckedChange = { v -> vm.update { it.copy(quietHours = it.quietHours.copy(enabled = v)) } })
            }
            Text("開始 ${s.quietHours.startHour} 時", style = MaterialTheme.typography.bodySmall)
            ChipRow(items = listOf(20, 21, 22, 23, 0, 1), selected = { it == s.quietHours.startHour }, onSelect = { h -> vm.update { it.copy(quietHours = it.quietHours.copy(startHour = h)) } }, label = { "$it" })
            Text("終了 ${s.quietHours.endHour} 時", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            ChipRow(items = listOf(4, 5, 6, 7, 8, 9), selected = { it == s.quietHours.endHour }, onSelect = { h -> vm.update { it.copy(quietHours = it.quietHours.copy(endHour = h)) } }, label = { "$it" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                NumberField("バイパス 分", s.bypassMinutes, Modifier.weight(1f)) { v -> if (v > 0) vm.update { it.copy(bypassMinutes = v) } }
                NumberField("バイパス 回/日", s.bypassesPerDay, Modifier.weight(1f)) { v -> vm.update { it.copy(bypassesPerDay = v) } }
            }

            SectionTitle("体力")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("最大体力", s.stamina.max, Modifier.weight(1f)) { v -> if (v > 0) vm.update { it.copy(stamina = it.stamina.copy(max = v)) } }
                NumberField("娯楽回復の上限/日", s.stamina.restDailyCap, Modifier.weight(1f)) { v -> vm.update { it.copy(stamina = it.stamina.copy(restDailyCap = v)) } }
            }
            val ratePct = (s.stamina.recoveryRate * 100).roundToInt()
            Text("朝の回復率：$ratePct%（睡眠申告があればそちらを優先）", modifier = Modifier.padding(top = 12.dp))
            var rate by remember(ratePct) { mutableStateOf(ratePct.toFloat()) }
            Slider(
                value = rate,
                onValueChange = { rate = it },
                onValueChangeFinished = { vm.update { it.copy(stamina = it.stamina.copy(recoveryRate = (rate.roundToInt() / 5 * 5) / 100.0)) } },
                valueRange = 50f..100f,
                steps = 9,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("休む回復 /15分", s.stamina.restRecoveryPer15Min, Modifier.weight(1f)) { v -> vm.update { it.copy(stamina = it.stamina.copy(restRecoveryPer15Min = v)) } }
                NumberField("1.5倍になる体力以下", s.stamina.lowThreshold, Modifier.weight(1f)) { v -> vm.update { it.copy(stamina = it.stamina.copy(lowThreshold = v)) } }
                NumberField("回復ゼロの体力以上", s.stamina.highThreshold, Modifier.weight(1f)) { v -> vm.update { it.copy(stamina = it.stamina.copy(highThreshold = v)) } }
            }

            SectionTitle("休む・3 択")
            var restText by remember(s.restMinuteOptions) { mutableStateOf(s.restMinuteOptions.joinToString(",")) }
            OutlinedTextField(
                value = restText,
                onValueChange = { v ->
                    restText = v
                    val parsed = v.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
                    if (parsed.isNotEmpty()) vm.update { it.copy(restMinuteOptions = parsed) }
                },
                label = { Text("休む時間の候補（分、カンマ区切り）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("引き直し回数/日", s.redrawsPerDay, Modifier.weight(1f)) { v -> vm.update { it.copy(redrawsPerDay = v) } }
                NumberField("SP 出現率 %", (s.spChance * 100).roundToInt(), Modifier.weight(1f)) { v -> vm.update { it.copy(spChance = (v.coerceIn(0, 100)) / 100.0) } }
            }
            Text("日付境界（この時刻に 1 日が切り替わる）", modifier = Modifier.padding(top = 12.dp))
            ChipRow(items = (0..8).toList(), selected = { it == s.dayBoundaryHour }, onSelect = { h -> vm.update { it.copy(dayBoundaryHour = h) } }, label = { "$it 時" })

            SectionTitle("週ランクの閾値")
            // 4 つまとめて検証する（S > A > B > C > 0）。途中の値や順序違いは保存せず、欄を赤くして知らせる。
            // 1 欄ずつ保存すると「S を 250 にしたい」が A=280 に引っかかって黙って捨てられる
            var rankS by remember(s.rank) { mutableStateOf(s.rank.s.toString()) }
            var rankA by remember(s.rank) { mutableStateOf(s.rank.a.toString()) }
            var rankB by remember(s.rank) { mutableStateOf(s.rank.b.toString()) }
            var rankC by remember(s.rank) { mutableStateOf(s.rank.c.toString()) }
            val rankCandidate = runCatching {
                RankThresholds(s = rankS.toInt(), a = rankA.toInt(), b = rankB.toInt(), c = rankC.toInt())
            }.getOrNull()
            val rankInvalid = rankCandidate == null
            fun commitRank(sS: String, sA: String, sB: String, sC: String) {
                val next = runCatching { RankThresholds(s = sS.toInt(), a = sA.toInt(), b = sB.toInt(), c = sC.toInt()) }.getOrNull() ?: return
                vm.update { it.copy(rank = next) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RankField("S", rankS, rankInvalid, Modifier.weight(1f)) { v -> rankS = v; commitRank(v, rankA, rankB, rankC) }
                RankField("A", rankA, rankInvalid, Modifier.weight(1f)) { v -> rankA = v; commitRank(rankS, v, rankB, rankC) }
                RankField("B", rankB, rankInvalid, Modifier.weight(1f)) { v -> rankB = v; commitRank(rankS, rankA, v, rankC) }
                RankField("C", rankC, rankInvalid, Modifier.weight(1f)) { v -> rankC = v; commitRank(rankS, rankA, rankB, v) }
            }
            if (rankInvalid) {
                Text(
                    "S > A > B > C > 0 の順になるように。直るまで保存されない（保存中の値：S ${s.rank.s} / A ${s.rank.a} / B ${s.rank.b} / C ${s.rank.c}）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp),
                )
            }

            SectionTitle("軸の名前")
            Axis.entries.forEach { axis ->
                var label by remember(s.axisLabels[axis]) { mutableStateOf(s.label(axis)) }
                OutlinedTextField(
                    value = label,
                    onValueChange = { v -> label = v; if (v.isNotBlank()) vm.update { it.copy(axisLabels = it.axisLabels + (axis to v)) } },
                    label = { Text(axis.defaultLabel) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            SectionTitle("バックアップ")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { exportLauncher.launch(BackupManager.suggestedFileName()) }, modifier = Modifier.weight(1f)) { Text("JSON に書き出す") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, modifier = Modifier.weight(1f)) { Text("JSON から復元") }
            }
            Text(
                "日付が変わるごとに Download/${BackupManager.AUTO_BACKUP_FOLDER} フォルダへ直近 ${BackupManager.KEEP_GENERATIONS} 世代を自動保存（アンインストールしても残る）。戻すときは「JSON から復元」でそのファイルを選ぶ。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(16.dp))
            Text("このアプリは通信しません（INTERNET 権限なし）。データは端末内だけ。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
        }
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("復元する？") },
            text = { Text("今のデータは全部置き換わる。先に書き出しておくと安心。") },
            confirmButton = { TextButton(onClick = { vm.importBackup(uri); confirmImport = null }) { Text("復元") } },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("やめる") } },
        )
    }
}

/** ランク閾値の 1 欄。検証は 4 欄まとめて行うので、ここでは数字だけ受け取って見た目（isError）を出す。 */
@Composable
private fun RankField(label: String, value: String, isError: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
