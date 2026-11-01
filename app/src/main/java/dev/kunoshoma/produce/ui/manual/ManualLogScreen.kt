package dev.kunoshoma.produce.ui.manual

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.ui.components.ChipRow
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.theme.StaminaGreen
import kotlinx.coroutines.delay

private val agoOptions = listOf(0, 15, 30, 60, 120, 180, 360, 720)
private fun agoLabel(m: Int) = when {
    m == 0 -> "今"
    m < 60 -> "$m 分前"
    else -> "${m / 60} 時間前"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualLogScreen(onBack: () -> Unit, vm: ManualLogViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(s.message, s.done) {
        val msg = s.message ?: return@LaunchedEffect
        if (s.done) {
            // 成功：結果はボタンの下に出してあるので、少し見せてすぐ戻る
            // （スナックバーが消えるまでの 4 秒を待たせると「固まった」ように見える）
            delay(600)
            onBack()
        } else {
            snackbar.showSnackbar(msg)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("後付け記録", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // edge-to-edge なので Scaffold の padding は IME を含まない。メモ入力中はキーボードの分も避ける
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            TabRow(selectedTabIndex = s.tab.ordinal) {
                Tab(selected = s.tab == ManualTab.LESSON, onClick = { vm.update { it.copy(tab = ManualTab.LESSON) } }, text = { Text("さっきやった") })
                Tab(selected = s.tab == ManualTab.REST, onClick = { vm.update { it.copy(tab = ManualTab.REST) } }, text = { Text("娯楽してた") })
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                when (s.tab) {
                    ManualTab.LESSON -> {
                        Axis.entries.forEach { axis ->
                            val list = templates.filter { it.axis == axis && it.enabled }
                            if (list.isEmpty()) return@forEach
                            SectionTitle(settings?.label(axis) ?: axis.defaultLabel)
                            ChipRow(items = list, selected = { it.id == s.templateId }, onSelect = { t -> vm.update { it.copy(templateId = t.id) } }, label = { "${it.name}（${it.durationMin}分）" })
                        }
                        SectionTitle("いつ始めた？")
                        ChipRow(items = agoOptions, selected = { it == s.minutesAgo }, onSelect = { m -> vm.update { it.copy(minutesAgo = m) } }, label = ::agoLabel)
                        SectionTitle("どうだった？")
                        ChipRow(items = LessonResult.entries, selected = { it == s.result }, onSelect = { r -> vm.update { it.copy(result = r) } }, label = { it.label })
                        if (s.result != LessonResult.SUCCESS) {
                            SectionTitle("理由")
                            ChipRow(items = FailReason.entries, selected = { it in s.reasons }, onSelect = vm::toggleReason, label = { it.label })
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = s.note, onValueChange = { v -> vm.update { it.copy(note = v) } }, label = { Text("メモ（任意）") }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    ManualTab.REST -> {
                        SectionTitle("どれくらい？")
                        ChipRow(items = listOf(15, 30, 60, 90, 120, 180), selected = { it == s.restMinutes }, onSelect = { m -> vm.update { it.copy(restMinutes = m) } }, label = { "$it 分" })
                        SectionTitle("いつ始めた？")
                        ChipRow(items = agoOptions, selected = { it == s.minutesAgo }, onSelect = { m -> vm.update { it.copy(minutesAgo = m) } }, label = ::agoLabel)
                        Spacer(Modifier.height(8.dp))
                        Text("PC やテレビで見た分など、アプリが検知できない娯楽を正直に。体力回復は通常ルールで入る。")
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = vm::save, enabled = !s.saving && !s.done, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (s.saving) "記録中…" else "記録する")
                }
                if (s.done) {
                    s.message?.let { Text(it, color = StaminaGreen, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp)) }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
