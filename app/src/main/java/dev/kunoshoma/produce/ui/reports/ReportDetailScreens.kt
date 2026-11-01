package dev.kunoshoma.produce.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.report.DailyReport
import dev.kunoshoma.produce.core.report.GoalSummary
import dev.kunoshoma.produce.core.report.WeeklyReport
import dev.kunoshoma.produce.core.rules.Judgement
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.ui.components.AxisDot
import dev.kunoshoma.produce.ui.components.Pill
import dev.kunoshoma.produce.ui.components.ProduceCard
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.theme.Accent
import dev.kunoshoma.produce.ui.theme.StaminaGreen
import dev.kunoshoma.produce.ui.theme.color
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    loaded: Boolean,
    empty: Boolean,
    error: String? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
    ) { padding ->
        when {
            !loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            empty -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("レポートが見つからない") }
            else -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                content()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun AxisGainRows(gains: Map<Axis, Int>, settings: Settings?, delta: ((Axis) -> Int)? = null) {
    Axis.entries.forEach { a ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            AxisDot(a)
            Spacer(Modifier.width(8.dp))
            Text(settings?.label(a) ?: a.defaultLabel, Modifier.weight(1f))
            delta?.invoke(a)?.let { d ->
                Text(if (d >= 0) "前週比 +$d" else "前週比 $d", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
            }
            Text("+${gains[a] ?: 0}", fontWeight = FontWeight.Bold, color = a.color())
        }
    }
}

/** 週のお題の軸と、その軸をやったかどうか（SPEC 3.4 / 5.2）。 */
@Composable
private fun ThemeRow(axis: Axis, settings: Settings?, gain: Int, hit: Boolean, hitLabel: String, missLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text("お題", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AxisDot(axis)
        Spacer(Modifier.width(8.dp))
        Text(settings?.label(axis) ?: axis.defaultLabel, Modifier.weight(1f))
        Text("+$gain", fontWeight = FontWeight.Bold, color = axis.color())
        Spacer(Modifier.width(12.dp))
        Pill(if (hit) hitLabel else missLabel, if (hit) StaminaGreen else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 中期目標の要約。日次は 1 行、週次は中間試験としてターゲットごとに（SPEC 12.4）。 */
@Composable
private fun GoalHeader(g: GoalSummary, pillPrefix: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(g.name, fontWeight = FontWeight.SemiBold)
            Text("期限 ${g.deadline}・あと ${g.daysLeft} 日", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Pill(pillPrefix + g.judgement.label, g.judgement.color())
    }
}

@Composable
private fun ExamLines(g: GoalSummary) {
    g.targets.forEach { t ->
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.label, Modifier.weight(1f))
            if (t.isChecklist) {
                // チェック項目は数字ではなく済/未済
                Text(if (t.judgement == Judgement.ACHIEVED) "チェック済" else "未チェック", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            } else if (t.remaining > 0) {
                Text("−${t.remaining}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            Pill(t.judgement.label, t.judgement.color())
        }
        if (t.judgement != Judgement.ACHIEVED && !t.isChecklist && t.requiredPerDay > 0.0) {
            val perWeek = (t.requiredPerDay * 7).roundToInt()
            val suggestion = t.suggestion?.let { "（$it）" } ?: ""
            Text(
                "来週の必要ペース：週 +$perWeek（1 日 +${"%.1f".format(t.requiredPerDay)}）$suggestion",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DailyReportScreen(date: String, onBack: () -> Unit, vm: DailyReportViewModel = hiltViewModel()) {
    val report by vm.report.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    DetailScaffold(title = "日次レポート $date", onBack = onBack, loaded = loaded, empty = report == null, error = error) {
        val r: DailyReport = report ?: return@DetailScaffold
        ProduceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("+${r.totalGain}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Accent)
                Spacer(Modifier.width(12.dp))
                Column {
                    Pill(r.condition.label, r.condition.color())
                    if (r.streak > 0) Text("${r.streak} 日連続", style = MaterialTheme.typography.bodySmall)
                    if (r.isOffDay) Text("オフ日", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(r.comment, style = MaterialTheme.typography.bodyMedium)
        }
        if (r.goal != null || r.themeAxis != null) {
            SectionTitle("目標・お題")
            ProduceCard {
                r.goal?.let { g ->
                    GoalHeader(g)
                    Spacer(Modifier.height(4.dp))
                    // SPEC 12.4：「目標：あと 12 日。趣味 −48（イラスト制作 ×4 回分）」の 1 行要約
                    Text("目標：${g.summaryLine}", style = MaterialTheme.typography.bodyMedium)
                }
                r.themeAxis?.let { a ->
                    if (r.goal != null) Spacer(Modifier.height(8.dp))
                    ThemeRow(a, settings, r.gains[a] ?: 0, r.themeHit, hitLabel = "今日やった", missLabel = "今日はなし")
                }
            }
        }
        SectionTitle("獲得")
        ProduceCard { AxisGainRows(r.gains, settings) }
        SectionTitle("レッスン")
        ProduceCard {
            Row { Text("回数", Modifier.weight(1f)); Text("${r.lessons}") }
            Row { Text("ちゃんとできた / 微妙 / 失敗", Modifier.weight(1f)); Text("${r.success} / ${r.partial} / ${r.failed}") }
            if (r.failReasons.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                r.failReasons.forEach { (reason, n) -> Row { Text(reason.label, Modifier.weight(1f)); Text("$n") } }
            }
        }
        SectionTitle("体力・娯楽")
        ProduceCard {
            Row { Text("体力", Modifier.weight(1f)); Text("${r.staminaStart} → ${r.staminaEnd}") }
            // 娯楽の合計は 休む + 自己申告 + バイパス（SPEC 5）。内訳を下に出す。
            Row { Text("娯楽（合計）", Modifier.weight(1f)); Text("${r.restMinutes} 分", fontWeight = FontWeight.Bold) }
            Row { Text("休む", Modifier.weight(1f).padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${r.pureRestMinutes} 分") }
            if (r.selfReportedMinutes > 0) Row { Text("自己申告", Modifier.weight(1f).padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${r.selfReportedMinutes} 分") }
            if (r.bypasses > 0) Row { Text("バイパス ${r.bypasses} 回", Modifier.weight(1f).padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${r.bypassMinutes} 分") }
            if (r.monitoringGapMinutes > 0) Row { Text("割り込み監視なし（娯楽に含めない）", Modifier.weight(1f)); Text("${r.monitoringGapMinutes} 分", color = MaterialTheme.colorScheme.error) }
        }
        SectionTitle("累計")
        ProduceCard { AxisGainRows(r.totalsAfter, settings) }
    }
}

@Composable
fun WeeklyReportScreen(weekStart: String, onBack: () -> Unit, vm: WeeklyReportViewModel = hiltViewModel()) {
    val report by vm.report.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    DetailScaffold(title = "週次レポート $weekStart 週", onBack = onBack, loaded = loaded, empty = report == null, error = error) {
        val r: WeeklyReport = report ?: return@DetailScaffold
        ProduceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.total} pt", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("レッスン ${r.lessons} 回 ・ 娯楽 ${r.restMinutes} 分 ・ オフ ${r.offDays} 日", style = MaterialTheme.typography.bodySmall)
                    Text("最長 ${r.longestStreak} 日連続 ・ 脱線率 ${(r.derailRate * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                Text(r.rank.name, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black, color = Accent)
            }
            Spacer(Modifier.height(8.dp))
            Text(r.comment, style = MaterialTheme.typography.bodyMedium)
        }
        SectionTitle("軸ごと")
        ProduceCard { AxisGainRows(r.gains, settings, delta = { r.delta(it) }) }
        if (r.themeAxis != null || r.goal != null) {
            SectionTitle("お題・中間試験")
            ProduceCard {
                r.themeAxis?.let { a ->
                    ThemeRow(
                        a, settings, r.themeGain, r.themeHit,
                        hitLabel = if (r.bestAxis == a) "一番伸びた！" else "取り組んだ",
                        missLabel = "手つかず",
                    )
                }
                r.goal?.let { g ->
                    if (r.themeAxis != null) Spacer(Modifier.height(8.dp))
                    // 週次レポート＝中間試験（SPEC 5.2 / 12.4）：軸ごとの判定・不足・来週の必要ペース
                    GoalHeader(g, pillPrefix = "中間試験：")
                    ExamLines(g)
                }
            }
        }
        SectionTitle("日ごと")
        ProduceCard {
            StackedBarsFromDays(r)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                listOf("月", "火", "水", "木", "金", "土", "日").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.height(8.dp))
            r.days.forEach { d ->
                Row {
                    Text(d.date.substring(5), Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                    Text(if (d.isOffDay) "オフ" else "レッスン ${d.lessons} 回", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("+${d.gain}", style = MaterialTheme.typography.bodySmall, color = StaminaGreen)
                    Spacer(Modifier.width(12.dp))
                    Text("娯楽 ${d.restMinutes} 分", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SectionTitle("ハイライト")
        ProduceCard {
            Row { Text("一番伸びた軸", Modifier.weight(1f)); Text(r.bestAxis?.let { settings?.label(it) ?: it.defaultLabel } ?: "—") }
            Row { Text("一番やったアクション", Modifier.weight(1f)); Text(r.topAction ?: "—") }
            if (r.failReasons.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("脱線の理由", fontWeight = FontWeight.SemiBold)
                r.failReasons.forEach { (reason, n) -> Row { Text(reason.label, Modifier.weight(1f)); Text("$n") } }
            }
        }
    }
}

/** 週次レポートの日別棒グラフ。軸の内訳は持っていないので合計を単色（Accent）で描く。軸の色を使うと趣味だけに見えてしまう。 */
@Composable
private fun StackedBarsFromDays(r: WeeklyReport) {
    val series = Axis.entries.first()
    StackedBars(values = r.days.map { d -> mapOf(series to d.gain) }, height = 120, colorOf = { Accent })
}
