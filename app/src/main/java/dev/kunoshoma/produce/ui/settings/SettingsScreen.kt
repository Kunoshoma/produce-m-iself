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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.content.Intent
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.ui.actions.NumberField
import dev.kunoshoma.produce.ui.components.ChipRow
import dev.kunoshoma.produce.ui.components.SectionTitle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onBlockedApps: () -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val accessibilityOn by vm.accessibilityEnabled.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("S", s.rank.s, Modifier.weight(1f)) { v -> vm.update { it.copy(rank = it.rank.copy(s = v)) } }
                NumberField("A", s.rank.a, Modifier.weight(1f)) { v -> vm.update { it.copy(rank = it.rank.copy(a = v)) } }
                NumberField("B", s.rank.b, Modifier.weight(1f)) { v -> vm.update { it.copy(rank = it.rank.copy(b = v)) } }
                NumberField("C", s.rank.c, Modifier.weight(1f)) { v -> vm.update { it.copy(rank = it.rank.copy(c = v)) } }
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

            Spacer(Modifier.height(16.dp))
            Text("このアプリは通信しません（INTERNET 権限なし）。データは端末内だけ。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
        }
    }
}
