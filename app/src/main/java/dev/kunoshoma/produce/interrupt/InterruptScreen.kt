package dev.kunoshoma.produce.interrupt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.rules.Choice
import dev.kunoshoma.produce.ui.components.StaminaBar
import dev.kunoshoma.produce.ui.produce.ChoiceCards
import dev.kunoshoma.produce.ui.produce.ProduceEvent
import dev.kunoshoma.produce.ui.produce.ProduceViewModel

/** 緊急バイパスに必要な長押し時間（SPEC 4.2：3 秒）。 */
private const val BYPASS_HOLD_MILLIS = 3_000L

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
    val snackbar = remember { SnackbarHostState() }
    var restPicker by remember { mutableStateOf<Choice.Rest?>(null) }
    var bypassHint by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    // レッスン中なら、どのモードで呼ばれても「レッスン中！」を見せる（レッスン中に 3 択を出さない）
    val lessonMode = mode == InterruptActivity.MODE_LESSON || home?.activeLesson != null

    // 戻る操作で娯楽アプリに戻さない。「やめてホームへ」と同じ扱いにする（SPEC 4.1）
    BackHandler { onGoHome() }

    LaunchedEffect(event) {
        when (val e = event) {
            ProduceEvent.LessonStarted -> { vm.consumeEvent(); onLessonStarted() }
            ProduceEvent.Done -> { vm.consumeEvent(); if (vm.lastPickWasSleep) onGoHome() else onRestStarted() }
            is ProduceEvent.Error -> { snackbar.showSnackbar(e.message); vm.consumeEvent() }
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
    // 長押し中のゲージ。指を離したら 0 に戻す
    LaunchedEffect(holding) {
        if (!holding) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val start = withFrameMillis { it }
        while (holding) {
            withFrameMillis { t -> holdProgress = ((t - start).toFloat() / BYPASS_HOLD_MILLIS).coerceIn(0f, 1f) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            // 画面のどこを押しても 3 秒でバイパス。Initial パスで見るのでカードやボタンの上でも効く。
            // 指が動いたら（スクロール）やめる。
            .pointerInput(packageName, lessonMode) {
                // レッスン中画面ではバイパスは効かない（ゲートはレッスン優先）ので、長押しで回数だけ消費しないようにする
                if (lessonMode) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val slop = viewConfiguration.touchSlop
                    holding = true
                    val released = try {
                        withTimeoutOrNull(BYPASS_HOLD_MILLIS) {
                            var done = false
                            while (!done) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                                done = change == null || !change.pressed || (change.position - down.position).getDistance() > slop
                            }
                            true
                        }
                    } finally {
                        holding = false
                    }
                    if (released == null) {
                        // 3 秒押し続けた。残りのイベントは Initial パスで消費して、指の下のカードの onClick を発火させない
                        bypassVm.requestBypass(packageName)
                        var pressed = true
                        while (pressed) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.forEach { it.consume() }
                            pressed = e.changes.any { it.pressed }
                        }
                    }
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (lessonMode) {
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
                    "どうしても今すぐ必要なら、画面のどこかを ${BYPASS_HOLD_MILLIS / 1000} 秒長押しで ${home?.settings?.bypassMinutes ?: 5} 分だけ通す" +
                        "（1 日 ${home?.settings?.bypassesPerDay ?: 3} 回、記録に残る）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        if (holding || holdProgress > 0f) {
            LinearProgressIndicator(
                progress = { holdProgress },
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
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
