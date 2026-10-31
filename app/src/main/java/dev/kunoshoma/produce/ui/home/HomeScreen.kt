package dev.kunoshoma.produce.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.core.model.SleepBucket
import dev.kunoshoma.produce.core.rules.RankRules
import dev.kunoshoma.produce.data.db.ActionLogEntity
import dev.kunoshoma.produce.data.db.DayKind
import dev.kunoshoma.produce.data.db.RestKind
import dev.kunoshoma.produce.data.db.RestLogEntity
import dev.kunoshoma.produce.domain.HomeState
import dev.kunoshoma.produce.ui.components.AxisRow
import dev.kunoshoma.produce.ui.components.Pill
import dev.kunoshoma.produce.ui.components.ProduceCard
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.components.StaminaBar
import dev.kunoshoma.produce.ui.theme.Accent
import dev.kunoshoma.produce.ui.theme.StaminaGreen
import dev.kunoshoma.produce.ui.theme.color
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onProduce: () -> Unit,
    onLesson: () -> Unit,
    onManual: () -> Unit,
    onActions: () -> Unit,
    onSettings: () -> Unit,
    onReports: () -> Unit = {},
    onGoal: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmSleep by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("プロデュース", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onReports) { Icon(Icons.Default.Assessment, contentDescription = "レポート") }
                    IconButton(onClick = onActions) { Icon(Icons.Default.Tune, contentDescription = "アクション管理") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "設定") }
                },
            )
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(s) }
            item { TodayCard(s) }
            item { GoalCard(s, onGoal) }
            if (s.shouldAskSleep) item { SleepAskCard(onPick = vm::reportSleep) }
            s.activeLesson?.let { lesson ->
                item {
                    ProduceCard {
                        Text("レッスン中：${lesson.templateName}", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onLesson, modifier = Modifier.fillMaxWidth()) { Text("タイマーを見る") }
                    }
                }
            }
            s.activeRest?.let { rest ->
                item {
                    ProduceCard {
                        Text("休憩中（${rest.allowedMin} 分）", fontWeight = FontWeight.Bold)
                        Text("開始 ${rest.startedAt.atZone(ZoneId.systemDefault()).format(timeFmt)}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = vm::endRest, modifier = Modifier.fillMaxWidth()) { Text("休憩を終える") }
                    }
                }
            }
            item { ActionButtons(s, onProduce, onManual, onSleep = { confirmSleep = true }, onOff = { confirmOff = true }, onReopen = vm::reopenDay, onUnOff = { vm.setOffDay(false) }) }
            if (s.todayLogs.isNotEmpty() || s.todayRests.isNotEmpty()) {
                item { SectionTitle("今日の記録") }
                items(s.todayLogs, key = { "l${it.id}" }) { LogRow(it, s, onDelete = { vm.deleteLog(it.id) }) }
                items(s.todayRests.filter { it.endedAt != null }, key = { "r${it.id}" }) { RestRow(it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmSleep) {
        AlertDialog(
            onDismissRequest = { confirmSleep = false },
            title = { Text("今日を終える？") },
            text = { Text("「寝る」を宣言すると今日のプロデュースは終了。明日の朝、睡眠時間を聞くよ。") },
            confirmButton = { TextButton(onClick = { vm.closeDay(); confirmSleep = false }) { Text("寝る") } },
            dismissButton = { TextButton(onClick = { confirmSleep = false }) { Text("まだ") } },
        )
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("今日はオフにする？") },
            text = { Text("オフ日はストリークが切れない。割り込みも止まる。罪悪感なしで休もう。") },
            confirmButton = { TextButton(onClick = { vm.setOffDay(true); confirmOff = false }) { Text("オフにする") } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun StatusCard(s: HomeState) {
    val maxForBar = (s.totals.values.maxOrNull() ?: 0).coerceAtLeast(100)
    ProduceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.date.toString(), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Pill(s.displayCondition.label, s.displayCondition.color())
            if (s.streak.displayStreak > 0) {
                Spacer(Modifier.width(6.dp))
                Pill("${s.streak.displayStreak} 日連続", Accent)
            }
            s.day.themeAxis?.let {
                Spacer(Modifier.width(6.dp))
                Pill("お題：${s.settings.label(it)}", it.color())
            }
        }
        Spacer(Modifier.height(12.dp))
        StaminaBar(current = s.day.stamina, max = s.settings.stamina.max)
        Spacer(Modifier.height(16.dp))
        Axis.entries.forEach { axis ->
            AxisRow(
                axis = axis,
                label = s.settings.label(axis),
                today = s.todayGains[axis] ?: 0,
                total = s.totals[axis] ?: 0,
                maxForBar = maxForBar,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun TodayCard(s: HomeState) {
    val rank = RankRules.rank(s.weekTotal, s.settings.rank)
    val toNext = RankRules.toNextRank(s.weekTotal, s.settings.rank)
    ProduceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("今週", style = MaterialTheme.typography.labelMedium)
                Text("${s.weekTotal} pt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (toNext == null) "ランク S 到達！" else "次のランクまで あと $toNext",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ランク", style = MaterialTheme.typography.labelMedium)
                Text(rank.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Accent)
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("今日", style = MaterialTheme.typography.labelMedium)
                Text("レッスン ${s.todayLogs.size} 回", style = MaterialTheme.typography.bodyMedium)
                val restMin = s.todayRests.sumOf { it.actualMin ?: 0 }
                Text("娯楽 $restMin 分", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (s.comebackEligible && s.day.kind != DayKind.OFF) {
            Spacer(Modifier.height(8.dp))
            Text("復帰ボーナス：次の 1 回は獲得 +30%", color = StaminaGreen, style = MaterialTheme.typography.bodySmall)
        }
        if (s.day.honestyBonusPending) {
            Text("正直ボーナス：次の 1 回は獲得 +10%", color = StaminaGreen, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GoalCard(s: HomeState, onGoal: () -> Unit) {
    val g = s.goal
    ProduceCard(Modifier.clickable(onClick = onGoal)) {
        if (g == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("中期目標", fontWeight = FontWeight.Bold)
                    Text("目標を置くと週ごとに中間試験が走って、足りない分を逆算してくれる", style = MaterialTheme.typography.bodySmall)
                }
                Text("設定 →", color = Accent)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontWeight = FontWeight.Bold)
                    Text("あと ${g.daysLeft} 日 ・ 進捗 ${(g.progressRatio * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                Pill(g.overall.label, g.overall.color())
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { g.progressRatio.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(g.summaryLine, style = MaterialTheme.typography.bodySmall)
            if (!g.feasible) Text("このペースは体力的に厳しい。目標画面で調整を", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SleepAskCard(onPick: (SleepBucket) -> Unit) {
    ProduceCard {
        Text("おはよう！昨日はどれくらい寝た？", fontWeight = FontWeight.Bold)
        Text("回復率に反映するよ", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepBucket.entries.forEach { b ->
                OutlinedButton(onClick = { onPick(b) }, modifier = Modifier.weight(1f)) { Text(b.label) }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    s: HomeState,
    onProduce: () -> Unit,
    onManual: () -> Unit,
    onSleep: () -> Unit,
    onOff: () -> Unit,
    onReopen: () -> Unit,
    onUnOff: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            s.isClosed -> {
                ProduceCard {
                    Text("おやすみ中", fontWeight = FontWeight.Bold)
                    Text("今日のプロデュースは終了。明日の朝にまた。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onReopen) { Text("やっぱり起きてる") }
                }
            }
            s.day.kind == DayKind.OFF -> {
                ProduceCard {
                    Text("今日はオフ日", fontWeight = FontWeight.Bold)
                    Text("ゆっくり休もう。ストリークは守られてる。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onUnOff) { Text("オフをやめる") }
                }
            }
            else -> {
                Button(
                    onClick = onProduce,
                    enabled = s.activeLesson == null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("プロデュース開始（3 択）", style = MaterialTheme.typography.titleMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onManual, modifier = Modifier.weight(1f)) { Text("さっきやった") }
                    OutlinedButton(onClick = onOff, modifier = Modifier.weight(1f)) { Text("今日はオフ") }
                    OutlinedButton(onClick = onSleep, modifier = Modifier.weight(1f)) { Text("寝る") }
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: ActionLogEntity, s: HomeState, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    ProduceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(log.templateName, fontWeight = FontWeight.SemiBold)
                    if (log.isSp) { Spacer(Modifier.width(6.dp)); Pill("SP", Accent) }
                    if (log.source == dev.kunoshoma.produce.data.db.LogSource.MANUAL) { Spacer(Modifier.width(6.dp)); Pill("手動", MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(
                    "${log.startedAt.atZone(ZoneId.systemDefault()).format(timeFmt)} ・ ${s.settings.label(log.axis)} ・ ${log.result.label}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (log.result == LessonResult.FAILED) "±0" else "+${log.gain}",
                color = log.axis.color(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = { confirm = true }) { Text("削除") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("この記録を消す？") },
            text = { Text("獲得は取り消され、体力は戻る。") },
            confirmButton = { TextButton(onClick = { onDelete(); confirm = false }) { Text("消す") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun RestRow(rest: RestLogEntity) {
    ProduceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (rest.kind) { RestKind.REST -> "休む"; RestKind.SELF_REPORT -> "娯楽（自己申告）"; RestKind.BYPASS -> "バイパス" },
                    fontWeight = FontWeight.SemiBold,
                )
                Text("${rest.startedAt.atZone(ZoneId.systemDefault()).format(timeFmt)} ・ ${rest.actualMin ?: rest.allowedMin} 分", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (rest.staminaRecovered > 0) "体力 +${rest.staminaRecovered}" else "回復なし", style = MaterialTheme.typography.bodyMedium, color = StaminaGreen)
        }
    }
}
