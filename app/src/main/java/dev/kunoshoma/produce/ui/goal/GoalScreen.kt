package dev.kunoshoma.produce.ui.goal

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.rules.Judgement
import dev.kunoshoma.produce.data.db.ExamKind
import dev.kunoshoma.produce.data.db.ExamResultEntity
import dev.kunoshoma.produce.data.db.GoalStatus
import dev.kunoshoma.produce.data.db.TargetKind
import dev.kunoshoma.produce.goal.GoalEvaluation
import dev.kunoshoma.produce.goal.TargetEvaluation
import dev.kunoshoma.produce.ui.components.Pill
import dev.kunoshoma.produce.ui.components.ProduceCard
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.theme.Accent
import dev.kunoshoma.produce.ui.theme.StaminaGreen
import dev.kunoshoma.produce.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalScreen(onBack: () -> Unit, onCreate: () -> Unit, vm: GoalViewModel = hiltViewModel()) {
    val loaded by vm.active.collectAsStateWithLifecycle()
    val exams by vm.exams.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val historyExams by vm.historyExams.collectAsStateWithLifecycle()
    var confirmAbandon by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("中期目標", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
    ) { padding ->
        val l = loaded
        if (l == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            val ui = l.ui
            if (ui == null) {
                Spacer(Modifier.height(24.dp))
                Text("いま目標はない", style = MaterialTheme.typography.titleMedium)
                Text("「この期間でここまで伸ばす」を宣言すると、週ごとに中間試験が走って足りない分を逆算してくれる。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("目標を作る") }
            } else {
                val ev = ui.evaluation
                ProduceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ui.goal.goal.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${ui.goal.goal.startDate} → ${ui.goal.goal.deadline}", style = MaterialTheme.typography.bodySmall)
                        }
                        ev?.let { Pill(it.overall.label, it.overall.color()) }
                    }
                    if (ev != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("あと ${ev.daysLeft} 日 ・ 進捗 ${(ev.progressRatio * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { ev.progressRatio.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                            color = Accent, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(ev.summaryLine, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (ev != null) {
                    SectionTitle("ターゲットと不足分")
                    ev.targets.forEach { t -> TargetCard(t, onToggle = { vm.toggleChecklist(t.targetId) }) }
                    SectionTitle("体力の実現可能性")
                    ProduceCard {
                        Text(
                            if (ev.feasible) "いけるペース。1 日あたり必要体力 ${"%.0f".format(ev.neededStaminaPerDay)} / 回復見込み ${"%.0f".format(ev.availableStaminaPerDay)}"
                            else "このペースは体力的に厳しい（必要 ${"%.0f".format(ev.neededStaminaPerDay)} / 回復見込み ${"%.0f".format(ev.availableStaminaPerDay)} の 8 割を超える）",
                            color = if (ev.feasible) StaminaGreen else MaterialTheme.colorScheme.error,
                        )
                        if (!ev.feasible) {
                            Spacer(Modifier.height(6.dp))
                            Text("対策：期限を延ばす / 目標値を下げる / 他の軸を緩める。いまは新しい目標を作り直すことで調整できる。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (exams.isNotEmpty()) {
                    SectionTitle("試験の履歴")
                    exams.forEach { e ->
                        ProduceCard { ExamBlock(e, vm.decode(e), initiallyExpanded = false) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCreate, modifier = Modifier.weight(1f)) { Text("作り直す") }
                    TextButton(onClick = { confirmAbandon = true }, modifier = Modifier.weight(1f)) { Text("この目標をやめる") }
                }
            }
            if (history.isNotEmpty()) {
                SectionTitle("過去の目標")
                history.forEach { g ->
                    ProduceCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.goal.name, fontWeight = FontWeight.SemiBold)
                                Text("${g.goal.startDate} → ${g.goal.deadline}", style = MaterialTheme.typography.bodySmall)
                            }
                            Pill(
                                when (g.goal.status) { GoalStatus.PASSED -> "合格"; GoalStatus.FAILED -> "不合格"; GoalStatus.ABANDONED -> "中断"; GoalStatus.ACTIVE -> "進行中" },
                                when (g.goal.status) { GoalStatus.PASSED -> StaminaGreen; GoalStatus.FAILED -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                            )
                        }
                        // 最終試験の結果（達成率・足りなかったもの）と中間試験は、目標が終わってもここから見返せる（SPEC 12.5）
                        val gExams = historyExams[g.goal.id].orEmpty()
                        val finals = gExams.filter { it.kind == ExamKind.FINAL }
                        val mids = gExams.filter { it.kind == ExamKind.MID }
                        finals.forEach { e ->
                            Spacer(Modifier.height(8.dp))
                            ExamBlock(e, vm.decode(e), initiallyExpanded = true)
                        }
                        if (mids.isNotEmpty()) {
                            var showMids by remember(g.goal.id) { mutableStateOf(false) }
                            TextButton(onClick = { showMids = !showMids }) {
                                Text(if (showMids) "中間試験を隠す" else "中間試験 ${mids.size} 回を見る")
                            }
                            if (showMids) {
                                mids.forEach { e ->
                                    ExamBlock(e, vm.decode(e), initiallyExpanded = false)
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                        if (g.goal.status == GoalStatus.FAILED && l.ui == null) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.retry(g.goal.id) }) { Text("同じ目標で再挑戦") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmAbandon) {
        val id = loaded?.ui?.goal?.goal?.id
        AlertDialog(
            onDismissRequest = { confirmAbandon = false },
            title = { Text("目標をやめる？") },
            text = { Text("履歴には「中断」として残る。") },
            confirmButton = { TextButton(onClick = { id?.let(vm::abandon); confirmAbandon = false }) { Text("やめる") } },
            dismissButton = { TextButton(onClick = { confirmAbandon = false }) { Text("続ける") } },
        )
    }
}

/**
 * 試験結果 1 件（SPEC 12.6 の結果カードの中身）。
 * 日付・中間/最終・合否・要約を常に出し、タップで保存時点の各ターゲットの達成状況（current / target、判定、不足分）を開く。
 */
@Composable
private fun ExamBlock(e: ExamResultEntity, detail: GoalEvaluation?, initiallyExpanded: Boolean) {
    var expanded by remember(e.id) { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = detail != null) { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.date.toString(), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Pill(if (e.kind == ExamKind.MID) "中間" else "最終", Accent)
            Spacer(Modifier.weight(1f))
            e.passed?.let { Text(if (it) "合格" else "不合格", fontWeight = FontWeight.Bold, color = if (it) StaminaGreen else MaterialTheme.colorScheme.error) }
        }
        Text(e.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (detail == null) return@Column
        if (!expanded) {
            Text("タップで各ターゲットの結果", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        Text("達成率 ${(detail.progressRatio * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        detail.targets.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(if (t.required) t.label else "${t.label}（加点）", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    if (t.kind == TargetKind.CHECKLIST) (if (t.current >= 1) "済" else "未") else "${t.current} / ${t.target}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(6.dp))
                Pill(t.judgement.label, t.judgement.color())
            }
            if (t.judgement != Judgement.ACHIEVED) {
                val shortfall = when (t.kind) {
                    TargetKind.CHECKLIST -> "未チェック"
                    TargetKind.ACTION_COUNT -> "あと ${(t.target - t.current).coerceAtLeast(0)} 回"
                    TargetKind.AXIS -> "あと ${(t.target - t.current).coerceAtLeast(0)}" + (t.suggestions.firstOrNull()?.let { "（$it）" } ?: "")
                }
                Text(shortfall, style = MaterialTheme.typography.bodySmall, color = Accent)
            }
        }
    }
}

@Composable
private fun TargetCard(t: TargetEvaluation, onToggle: () -> Unit) {
    ProduceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.kind == TargetKind.CHECKLIST) {
                Checkbox(checked = t.current >= 1, onCheckedChange = { onToggle() })
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.label, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    if (!t.required) Pill("加点", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (t.kind != TargetKind.CHECKLIST) {
                    Text("${t.current} / ${t.target}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Pill(t.judgement.label, t.judgement.color())
        }
        if (t.kind != TargetKind.CHECKLIST) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (t.current.toFloat() / t.target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = t.axis?.color() ?: Accent, trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (t.judgement != Judgement.ACHIEVED) {
                Spacer(Modifier.height(6.dp))
                Text("必要ペース：1 日 ${"%.1f".format(t.requiredPerDay)}（このままだと ${t.projected} 着地）", style = MaterialTheme.typography.bodySmall)
                if (t.suggestions.isNotEmpty()) {
                    Text("あと " + t.suggestions.joinToString(" か "), style = MaterialTheme.typography.bodySmall, color = Accent)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
