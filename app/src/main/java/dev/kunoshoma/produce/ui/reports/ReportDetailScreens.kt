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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.report.DailyReport
import dev.kunoshoma.produce.core.report.WeeklyReport
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.ui.components.AxisDot
import dev.kunoshoma.produce.ui.components.Pill
import dev.kunoshoma.produce.ui.components.ProduceCard
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.theme.Accent
import dev.kunoshoma.produce.ui.theme.StaminaGreen
import dev.kunoshoma.produce.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, loaded: Boolean, empty: Boolean, content: @Composable () -> Unit) {
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

@Composable
fun DailyReportScreen(date: String, onBack: () -> Unit, vm: DailyReportViewModel = hiltViewModel()) {
    val report by vm.report.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    DetailScaffold(title = "日次レポート $date", onBack = onBack, loaded = loaded, empty = report == null) {
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
            Row { Text("休む", Modifier.weight(1f)); Text("${r.restMinutes} 分") }
            if (r.selfReportedMinutes > 0) Row { Text("娯楽（自己申告）", Modifier.weight(1f)); Text("${r.selfReportedMinutes} 分") }
            if (r.bypasses > 0) Row { Text("バイパス", Modifier.weight(1f)); Text("${r.bypasses} 回") }
            if (r.monitoringGapMinutes > 0) Row { Text("割り込み監視なし", Modifier.weight(1f)); Text("${r.monitoringGapMinutes} 分", color = MaterialTheme.colorScheme.error) }
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
    DetailScaffold(title = "週次レポート $weekStart 週", onBack = onBack, loaded = loaded, empty = report == null) {
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

/** 週次レポートの日別棒グラフ。軸の内訳は持っていないので合計を単色で。 */
@Composable
private fun StackedBarsFromDays(r: WeeklyReport) {
    StackedBars(values = r.days.map { d -> mapOf(Axis.HOBBY to d.gain) }, height = 120)
}
