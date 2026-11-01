package dev.kunoshoma.produce.ui.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.DayPart
import dev.kunoshoma.produce.data.db.ActionTemplateEntity
import dev.kunoshoma.produce.ui.components.AxisDot
import dev.kunoshoma.produce.ui.components.ChipRow
import dev.kunoshoma.produce.ui.components.ProduceCard
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: ActionsViewModel = hiltViewModel()) {
    val templates by vm.templates.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アクション管理", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(0L) }) { Icon(Icons.Default.Add, contentDescription = "追加") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Axis.entries.forEach { axis ->
                val list = templates.filter { it.axis == axis }
                item(key = "h_$axis") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        AxisDot(axis)
                        Spacer(Modifier.width(8.dp))
                        Text(settings?.label(axis) ?: axis.defaultLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = axis.color())
                    }
                }
                if (list.isEmpty()) item(key = "e_$axis") { Text("まだない。＋ で追加", style = MaterialTheme.typography.bodySmall) }
                items(list, key = { it.id }) { t -> TemplateRow(t, onClick = { onEdit(t.id) }, onToggle = { vm.setEnabled(t.id, it) }) }
            }
        }
    }
}

@Composable
private fun TemplateRow(t: ActionTemplateEntity, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    ProduceCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${t.durationMin} 分 ・ 体力 -${t.staminaCost} ・ 獲得 +${t.baseGain} ・ 重み ${t.weight}" +
                        if (t.windows.isEmpty()) "" else " ・ " + t.windows.joinToString("/") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = t.enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionEditScreen(id: Long, onBack: () -> Unit, vm: ActionEditViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(s.saved) { if (s.saved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == 0L) "アクションを追加" else "アクションを編集", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
                actions = { if (id != 0L) TextButton(onClick = { confirmDelete = true }, enabled = !s.saving && !s.saved) { Text("削除") } },
            )
        },
    ) { padding ->
        if (!s.loaded) return@Scaffold
        // edge-to-edge なので Scaffold の padding は IME を含まない。文字入力中は IME の分も避けてスクロールできるようにする
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = s.name, onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle("軸")
            ChipRow(items = Axis.entries, selected = { it == s.axis }, onSelect = { a -> vm.update { it.copy(axis = a) } }, label = { it.defaultLabel })
            SectionTitle("時間（分）")
            ChipRow(items = listOf(15, 30, 45, 60, 90), selected = { it == s.durationMin }, onSelect = vm::setDuration, label = { "$it" })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("体力コスト", s.staminaCost, Modifier.weight(1f)) { v -> vm.update { it.copy(staminaCost = v) } }
                NumberField("獲得量", s.baseGain, Modifier.weight(1f)) { v -> vm.update { it.copy(baseGain = v) } }
            }
            SectionTitle("出現しやすさ：${s.weight}")
            Slider(value = s.weight.toFloat(), onValueChange = { v -> vm.update { it.copy(weight = v.toInt().coerceIn(1, 5)) } }, valueRange = 1f..5f, steps = 3)
            SectionTitle("出る時間帯（未選択ならいつでも）")
            ChipRow(items = DayPart.entries, selected = { it in s.windows }, onSelect = vm::toggleWindow, label = { it.label })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Text("有効", Modifier.weight(1f))
                Switch(checked = s.enabled, onCheckedChange = { v -> vm.update { it.copy(enabled = v) } })
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(24.dp))
            Button(onClick = vm::save, enabled = s.valid && !s.saving && !s.saved, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("保存") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("このアクションを削除？") },
            text = { Text("過去の記録は残る。3 択には出なくなる。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }, enabled = !s.saving) { Text("削除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

/**
 * 整数入力欄。入力中の文字列はこの欄が持ち、[value] は「外から値が変わったとき」だけ表示に反映する。
 *
 * この欄が [onChange] で出した値がそのまま親から戻ってくるぶん（エコー）では表示を書き換えない。
 * 設定画面のように DataStore 経由で遅れて戻る場合、"2" → "25" → "250" と打ったあとに "2" のエコーが
 * 届いて入力中の文字が消える・カーソルが飛ぶ、というのを防ぐ。
 * 時間チップでコスト・獲得の目安が入るような外部変更は、出した値と違うので今まで通り表示に反映される。
 */
@Composable
fun NumberField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    // onChange で出したが、まだ親から戻ってきていない値（古い順）
    val pending = remember { mutableListOf<Int>() }
    LaunchedEffect(value) {
        val i = pending.indexOf(value)
        if (i >= 0) {
            // 自分が出した値のエコー。それ以前の（追い越された）エコーも用済み
            pending.subList(0, i + 1).clear()
        } else {
            pending.clear()
            if (text.toIntOrNull() != value) text = value.toString()
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(4)
            text.toIntOrNull()?.let { n ->
                if (pending.size >= 16) pending.removeAt(0)
                pending.add(n)
                onChange(n)
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
