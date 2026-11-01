package dev.kunoshoma.produce.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.ui.components.Pill
import dev.kunoshoma.produce.ui.theme.Accent
import dev.kunoshoma.produce.ui.theme.color
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun LessonScreen(onFinish: () -> Unit, onBack: () -> Unit, vm: LessonViewModel = hiltViewModel()) {
    val loaded by vm.lesson.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmStop by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    val l = loaded
    if (l == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val lesson = l.lesson
    if (lesson == null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("進行中のレッスンはないよ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("ホームへ") }
        }
        return
    }

    val total = Duration.between(lesson.startedAt, lesson.plannedEndAt).seconds.coerceAtLeast(1)
    val elapsed = Duration.between(lesson.startedAt, Instant.ofEpochMilli(nowMillis)).seconds.coerceIn(0, total)
    val remaining = total - elapsed
    val done = remaining <= 0
    val progress = elapsed.toFloat() / total

    // Activity は edge-to-edge。Scaffold を使っていないので、ステータスバー / ナビバーの分はここで避ける
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Pill(settings?.label(lesson.axis) ?: lesson.axis.defaultLabel, lesson.axis.color())
        Spacer(Modifier.height(12.dp))
        Text(lesson.templateName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (lesson.isSp) { Spacer(Modifier.height(8.dp)); Pill("SP レッスン", Accent) }
        Spacer(Modifier.height(32.dp))
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(240.dp),
                color = lesson.axis.color(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 14.dp,
            )
            Text(
                if (done) "完了！" else "%02d:%02d".format(remaining / 60, remaining % 60),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(32.dp))
        if (done) {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("結果を記録する") }
        } else {
            Text("体力 -${lesson.staminaCost} ・ 獲得 +${lesson.baseGain}${if (lesson.isSp) " ×1.5" else ""}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth()) { Text("早めに終える / 中断") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("ホームに戻る（タイマーは続く）") }
        }
    }

    if (confirmStop) {
        // 中断した時刻は結果画面（ResultViewModel）が開いた瞬間に捕まえて finishLesson(endedAt) に渡す。
        // 入力に時間がかかっても、獲得の経過比例は「終える」を押した時点で計算される（SPEC 3.5）。
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("ここで終える？") },
            text = { Text("経過時間に応じた獲得（最低 50%）になる。体力は全額消費。") },
            confirmButton = { TextButton(onClick = { confirmStop = false; onFinish() }) { Text("終える") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("続ける") } },
        )
    }
}
