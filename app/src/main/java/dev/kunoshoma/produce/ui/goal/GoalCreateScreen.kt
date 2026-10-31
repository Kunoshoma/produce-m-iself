package dev.kunoshoma.produce.ui.goal

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.ui.actions.NumberField
import dev.kunoshoma.produce.ui.components.ChipRow
import dev.kunoshoma.produce.ui.components.Pill
import dev.kunoshoma.produce.ui.components.ProduceCard
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.theme.Accent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalCreateScreen(onBack: () -> Unit, vm: GoalCreateViewModel = hiltViewModel()) {
    val d by vm.draft.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    var axis by remember { mutableStateOf(Axis.HEALTH) }
    var axisValue by remember { mutableIntStateOf(200) }
    var templateId by remember { mutableStateOf<Long?>(null) }
    var count by remember { mutableIntStateOf(12) }
    var checklist by remember { mutableStateOf("") }
    LaunchedEffect(d.saved) { if (d.saved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("目標を作る", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("テンプレから")
            ChipRow(items = vm.presets, selected = { it.name == d.name }, onSelect = vm::applyPreset, label = { it.name })

            SectionTitle("名前と期限")
            OutlinedTextField(value = d.name, onValueChange = { v -> vm.update { it.copy(name = v) } }, label = { Text("目標の名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            ChipRow(items = listOf(2, 4, 6, 8, 12), selected = { it == d.weeks }, onSelect = { w -> vm.update { it.copy(weeks = w) } }, label = { "$it 週" })
            Text("期限：${d.deadline}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))

            SectionTitle("ターゲット（${d.targets.size}）")
            if (d.targets.isEmpty()) Text("下から追加。必須のものが全部達成で最終試験に合格。", style = MaterialTheme.typography.bodySmall)
            d.targets.forEachIndexed { i, t ->
                ProduceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.label, fontWeight = FontWeight.SemiBold)
                            val perDay = vm.perDay(t)
                            if (t.kind != dev.kunoshoma.produce.data.db.TargetKind.CHECKLIST) {
                                Text("1 日あたり ${"%.1f".format(perDay)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { vm.toggleRequired(i) }) { Pill(if (t.required) "必須" else "加点", if (t.required) Accent else MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { vm.removeTarget(i) }) { Text("削除") }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            SectionTitle("軸の獲得量を追加")
            ChipRow(items = Axis.entries, selected = { it == axis }, onSelect = { axis = it }, label = { settings?.label(it) ?: it.defaultLabel })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                NumberField("獲得量", axisValue, Modifier.weight(1f)) { axisValue = it }
                FilledTonalButton(onClick = { if (axisValue > 0) vm.addAxisTarget(axis, axisValue, settings?.label(axis) ?: axis.defaultLabel) }) { Text("追加") }
            }

            SectionTitle("アクション回数を追加")
            val enabled = templates.filter { it.enabled }
            ChipRow(items = enabled, selected = { it.id == templateId }, onSelect = { templateId = it.id }, label = { it.name })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                NumberField("回数", count, Modifier.weight(1f)) { count = it }
                FilledTonalButton(onClick = { enabled.firstOrNull { it.id == templateId }?.let { t -> if (count > 0) vm.addActionTarget(t, count) } }, enabled = templateId != null) { Text("追加") }
            }

            SectionTitle("チェック項目を追加")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = checklist, onValueChange = { checklist = it }, label = { Text("例：ネームを描き終える") }, singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { if (checklist.isNotBlank()) { vm.addChecklist(checklist); checklist = "" } }) { Text("追加") }
            }

            d.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(24.dp))
            Button(onClick = vm::save, enabled = d.valid && !d.saving, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("この目標で始める") }
            Text("進行中の目標がある場合は中断扱いになって置き換わる。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}
