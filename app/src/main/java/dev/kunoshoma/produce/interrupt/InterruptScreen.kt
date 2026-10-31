package dev.kunoshoma.produce.interrupt

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.rules.Choice
import dev.kunoshoma.produce.ui.components.StaminaBar
import dev.kunoshoma.produce.ui.produce.ChoiceCards
import dev.kunoshoma.produce.ui.produce.ProduceEvent
import dev.kunoshoma.produce.ui.produce.ProduceViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InterruptScreen(
    packageName: String?,
    mode: String,
    onLessonStarted: () -> Unit,
    onRestStarted: () -> Unit,
    onGoHome: () -> Unit,
    onBypass: () -> Unit,
    vm: ProduceViewModel = hiltViewModel(),
    bypassVm: BypassViewModel = hiltViewModel(),
) {
    val home by vm.home.collectAsStateWithLifecycle()
    val choices by vm.choices.collectAsStateWithLifecycle()
    val event by vm.event.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val bypass by bypassVm.state.collectAsStateWithLifecycle()
    var restPicker by remember { mutableStateOf<Choice.Rest?>(null) }
    var bypassHint by remember { mutableStateOf(false) }

    LaunchedEffect(event) {
        when (event) {
            ProduceEvent.LessonStarted -> { vm.consumeEvent(); onLessonStarted() }
            ProduceEvent.Done -> { vm.consumeEvent(); if (vm.lastPickWasSleep) onGoHome() else onRestStarted() }
            is ProduceEvent.Error -> vm.consumeEvent()
            null -> Unit
        }
    }
    LaunchedEffect(bypass) {
        when (bypass) {
            BypassState.Granted -> onBypass()
            BypassState.Denied -> bypassHint = true
            else -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .combinedClickable(onClick = {}, onLongClick = { bypassVm.requestBypass(packageName) })
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mode == InterruptActivity.MODE_LESSON) {
            Spacer(Modifier.height(48.dp))
            Text("レッスン中！", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            Text("${home?.activeLesson?.templateName ?: ""} が進行中。終わってからにしよう。", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("戻る") }
            OutlinedButton(onClick = onLessonStarted, modifier = Modifier.fillMaxWidth()) { Text("タイマーを見る") }
        } else {
            Text("どうする？", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            home?.let { StaminaBar(current = it.day.stamina, max = it.settings.stamina.max) }
            ChoiceCards(
                choices = choices,
                settings = home?.settings,
                enabled = !busy,
                onLesson = vm::pickLesson,
                onRest = { c -> if (c.minuteOptions.size == 1) vm.pickRest(c.minuteOptions.first(), packageName) else restPicker = c },
                onSleep = vm::pickSleep,
            )
            home?.let { s ->
                val left = s.redrawsLeft
                OutlinedButton(onClick = { vm.draw(redraw = true) }, enabled = left > 0 && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (left > 0) "引き直す（あと $left 回）" else "今日はもう引き直せない")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "どうしても今すぐ必要なら、画面を長押しで ${home?.settings?.bypassMinutes ?: 5} 分だけ通す（1 日 ${home?.settings?.bypassesPerDay ?: 3} 回、記録に残る）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    restPicker?.let { rest ->
        AlertDialog(
            onDismissRequest = { restPicker = null },
            title = { Text("どれくらい休む？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rest.minuteOptions.forEach { m ->
                        OutlinedButton(onClick = { restPicker = null; vm.pickRest(m, packageName) }, modifier = Modifier.fillMaxWidth()) { Text("$m 分") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { restPicker = null }) { Text("やめる") } },
        )
    }
    if (bypassHint) {
        AlertDialog(
            onDismissRequest = { bypassHint = false; bypassVm.reset() },
            title = { Text("今日のバイパスは使い切った") },
            text = { Text("3 択から選ぼう。") },
            confirmButton = { TextButton(onClick = { bypassHint = false; bypassVm.reset() }) { Text("OK") } },
        )
    }
}
