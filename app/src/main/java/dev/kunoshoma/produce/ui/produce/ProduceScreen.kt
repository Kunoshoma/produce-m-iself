package dev.kunoshoma.produce.ui.produce

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.rules.Badge
import dev.kunoshoma.produce.core.rules.Choice
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.ui.components.AxisDot
import dev.kunoshoma.produce.ui.components.Pill
import dev.kunoshoma.produce.ui.components.StaminaBar
import dev.kunoshoma.produce.ui.theme.Accent
import dev.kunoshoma.produce.ui.theme.StaminaGreen
import dev.kunoshoma.produce.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProduceScreen(
    onBack: () -> Unit,
    onLessonStarted: () -> Unit,
    onDone: () -> Unit,
    vm: ProduceViewModel = hiltViewModel(),
) {
    val home by vm.home.collectAsStateWithLifecycle()
    val choices by vm.choices.collectAsStateWithLifecycle()
    val event by vm.event.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var restPicker by remember { mutableStateOf<Choice.Rest?>(null) }

    LaunchedEffect(event) {
        when (val e = event) {
            ProduceEvent.LessonStarted -> { vm.consumeEvent(); onLessonStarted() }
            ProduceEvent.Done -> { vm.consumeEvent(); onDone() }
            is ProduceEvent.Error -> { snackbar.showSnackbar(e.message); vm.consumeEvent() }
            null -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("どうする？", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = home
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s != null) StaminaBar(current = s.day.stamina, max = s.settings.stamina.max, modifier = Modifier.padding(top = 8.dp))
            ChoiceCards(
                choices = choices,
                settings = s?.settings,
                enabled = !busy,
                onLesson = vm::pickLesson,
                onRest = { c -> if (c.minuteOptions.size == 1) vm.pickRest(c.minuteOptions.first()) else restPicker = c },
                onSleep = vm::pickSleep,
            )
            if (s != null) {
                val left = s.redrawsLeft
                OutlinedButton(
                    onClick = { vm.draw(redraw = true) },
                    enabled = left > 0 && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (left > 0) "引き直す（あと $left 回）" else "今日はもう引き直せない") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    restPicker?.let { rest ->
        AlertDialog(
            onDismissRequest = { restPicker = null },
            title = { Text("どれくらい休む？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rest.minuteOptions.forEach { m ->
                        OutlinedButton(onClick = { restPicker = null; vm.pickRest(m) }, modifier = Modifier.fillMaxWidth()) { Text("$m 分") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { restPicker = null }) { Text("やめる") } },
        )
    }
}

/** 3 択のカード群。ホームからの画面と割り込み画面で共用する。 */
@Composable
fun ChoiceCards(
    choices: List<Choice>,
    settings: Settings?,
    enabled: Boolean,
    onLesson: (Choice.Lesson) -> Unit,
    onRest: (Choice.Rest) -> Unit,
    onSleep: () -> Unit,
) {
    choices.forEach { c ->
        when (c) {
            is Choice.Lesson -> LessonCard(c, settings, enabled) { onLesson(c) }
            is Choice.Rest -> RestCard(c, enabled) { onRest(c) }
            Choice.Sleep -> SleepCard(enabled, onSleep)
        }
    }
}

@Composable
private fun ChoiceCard(border: Color, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(2.dp, border.copy(alpha = 0.7f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun LessonCard(c: Choice.Lesson, settings: Settings?, enabled: Boolean, onClick: () -> Unit) {
    val t = c.template
    val color = if (c.isSp) Accent else t.axis.color()
    ChoiceCard(border = color, enabled = enabled, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AxisDot(t.axis, 12)
            Spacer(Modifier.width(8.dp))
            Text(settings?.label(t.axis) ?: t.axis.defaultLabel, style = MaterialTheme.typography.labelLarge, color = t.axis.color())
            Spacer(Modifier.weight(1f))
            c.badges.forEach { b ->
                Pill(b.label, when (b) { Badge.SP -> Accent; Badge.EXAM_PREP -> MaterialTheme.colorScheme.error; Badge.THEME -> t.axis.color() })
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(t.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val gain = if (c.isSp) (t.baseGain * 1.5).toInt() else t.baseGain
        Row {
            Text("${t.durationMin} 分", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(16.dp))
            Text("体力 -${t.staminaCost}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(16.dp))
            Text("獲得 +$gain${if (c.isSp) "（SP）" else ""}", style = MaterialTheme.typography.bodyMedium, color = StaminaGreen, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RestCard(c: Choice.Rest, enabled: Boolean, onClick: () -> Unit) {
    ChoiceCard(border = MaterialTheme.colorScheme.onSurfaceVariant, enabled = enabled, onClick = onClick) {
        Text("休む", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("娯楽タイム", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (c.recovers) "${c.minuteOptions.joinToString(" / ")} 分 ・ 体力が少し回復"
            else "${c.minuteOptions.joinToString(" / ")} 分 ・ 今日はもう回復しない（上限か、体力が十分）",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SleepCard(enabled: Boolean, onClick: () -> Unit) {
    // 寝る宣言は翌朝まで割り込みが止まるので、ホームの「寝る」と同じく一度確認する
    var confirm by remember { mutableStateOf(false) }
    ChoiceCard(border = Accent, enabled = enabled, onClick = { confirm = true }) {
        Text("寝る", style = MaterialTheme.typography.labelLarge, color = Accent)
        Spacer(Modifier.height(8.dp))
        Text("今日を終える", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("今日はここまで。無理せず明日に回そう。", style = MaterialTheme.typography.bodyMedium)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("今日を終える？") },
            text = { Text("日次レポートを作って、明朝の日付境界まで割り込みを止める。娯楽は通るようになる。") },
            confirmButton = { TextButton(onClick = { confirm = false; onClick() }) { Text("寝る") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } },
        )
    }
}
