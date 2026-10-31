package dev.kunoshoma.produce.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.data.db.DailyReportEntity
import dev.kunoshoma.produce.data.db.WeeklyReportEntity
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.ui.components.AxisDot
import dev.kunoshoma.produce.ui.components.ProduceCard
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.theme.Accent
import dev.kunoshoma.produce.ui.theme.color
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val md = DateTimeFormatter.ofPattern("M/d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    onDaily: (String) -> Unit,
    onWeekly: (String) -> Unit,
    vm: ReportsViewModel = hiltViewModel(),
) {
    val daily by vm.daily.collectAsStateWithLifecycle()
    val weekly by vm.weekly.collectAsStateWithLifecycle()
    val trend by vm.trend.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("レポート", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("推移") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("日次") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("週次") })
            }
            when (tab) {
                0 -> TrendTab(trend, settings)
                1 -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (daily.isEmpty()) item { Text("まだない。最初の日が終わる（朝 5 時か「寝る」）と作られるよ。") }
                    items(daily, key = { it.date.toString() }) { DailyRow(it) { onDaily(it.date.toString()) } }
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (weekly.isEmpty()) item { Text("まだない。最初の週（月〜日）が終わると作られるよ。") }
                    items(weekly, key = { it.weekStart.toString() }) { WeeklyRow(it) { onWeekly(it.weekStart.toString()) } }
                }
            }
        }
    }
}

@Composable
private fun TrendTab(trend: Map<LocalDate, Map<Axis, Int>>, settings: Settings?) {
    val dates = trend.keys.sorted()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("直近 30 日の獲得（1 日ごと）")
        ProduceCard {
            if (dates.isEmpty()) {
                Text("データなし")
            } else {
                StackedBars(dates.map { d -> trend[d] ?: emptyMap() })
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(dates.first().format(md), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(dates.last().format(md), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ProduceCard {
            Axis.entries.forEach { a ->
                val sum = dates.sumOf { trend[it]?.get(a) ?: 0 }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    AxisDot(a)
                    Spacer(Modifier.width(8.dp))
                    Text(settings?.label(a) ?: a.defaultLabel, Modifier.weight(1f))
                    Text("+$sum / 30日", fontWeight = FontWeight.Bold, color = a.color())
                }
            }
        }
    }
}

/** 軸ごとに積み上げた棒グラフ。依存ライブラリなしで Canvas に描く。 */
@Composable
fun StackedBars(values: List<Map<Axis, Int>>, modifier: Modifier = Modifier, height: Int = 160) {
    val max = (values.maxOfOrNull { it.values.sum() } ?: 0).coerceAtLeast(1)
    val colors = Axis.entries.associateWith { it.color() }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        val n = values.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        values.forEachIndexed { i, day ->
            var y = size.height
            val x = i * (barW + gap)
            Axis.entries.forEach { a ->
                val v = day[a] ?: 0
                if (v > 0) {
                    val h = size.height * v / max
                    drawLine(colors.getValue(a), Offset(x + barW / 2, y), Offset(x + barW / 2, y - h), strokeWidth = barW, cap = StrokeCap.Butt)
                    y -= h
                }
            }
        }
    }
}

@Composable
private fun DailyRow(e: DailyReportEntity, onClick: () -> Unit) {
    ProduceCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.date.format(md), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(e.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.JAPANESE), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("+${e.totalGain}", fontWeight = FontWeight.Bold, color = Accent)
        }
    }
}

@Composable
private fun WeeklyRow(e: WeeklyReportEntity, onClick: () -> Unit) {
    ProduceCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${e.weekStart.format(md)} 週", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${e.total} pt", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Text(e.rank, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Accent)
        }
    }
}
