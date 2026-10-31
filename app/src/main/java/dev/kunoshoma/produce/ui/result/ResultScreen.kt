package dev.kunoshoma.produce.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.data.db.ActionLogEntity
import dev.kunoshoma.produce.ui.components.ChipRow
import dev.kunoshoma.produce.ui.components.ProduceCard
import dev.kunoshoma.produce.ui.components.SectionTitle
import dev.kunoshoma.produce.ui.theme.StaminaGreen
import dev.kunoshoma.produce.ui.theme.color

@Composable
fun ResultScreen(onHome: () -> Unit, vm: ResultViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    if (s.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    s.saved?.let { saved ->
        SavedView(saved, onHome)
        return
    }
    val lesson = s.lesson
    if (lesson == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("記録するレッスンがないよ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onHome) { Text("ホームへ") }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(lesson.templateName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (lesson.derailCount > 0) {
            Text("レッスン中に娯楽アプリを ${lesson.derailCount} 回開いてたみたい", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        SectionTitle("どうだった？")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LessonResult.entries.forEach { r ->
                val selected = s.result == r
                if (selected) {
                    Button(onClick = { vm.setResult(r) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(r.label) }
                } else {
                    OutlinedButton(onClick = { vm.setResult(r) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(r.label) }
                }
            }
        }
        Text(
            when (s.result) {
                LessonResult.SUCCESS, null -> "獲得 100%・体力は全額消費"
                LessonResult.PARTIAL -> "獲得 50%・体力は全額消費"
                LessonResult.FAILED -> "獲得 0・体力は半額・ストリークは続く。正直に言ったら次 +10%"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (s.result != LessonResult.SUCCESS) {
            SectionTitle("理由（複数OK）")
            ChipRow(
                items = FailReason.entries,
                selected = { it in s.reasons },
                onSelect = vm::toggleReason,
                label = { it.label },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = s.note,
                onValueChange = vm::setNote,
                label = { Text("メモ（任意）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::save, enabled = !s.saving && s.result != null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("記録する")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SavedView(saved: ActionLogEntity, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(saved.result.label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (saved.gain > 0) "+${saved.gain}" else "±0",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            color = saved.axis.color(),
        )
        Text(saved.templateName, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        ProduceCard {
            Row { Text("基礎", Modifier.weight(1f)); Text("${saved.baseGain}") }
            if (saved.isSp) Row { Text("SP", Modifier.weight(1f)); Text("×1.5") }
            if (saved.condition.bonus > 0) Row { Text(saved.condition.label, Modifier.weight(1f)); Text("+${(saved.condition.bonus * 100).toInt()}%") }
            if (saved.comebackBonus) Row { Text("復帰ボーナス", Modifier.weight(1f)); Text("+30%") }
            if (saved.honestyBonus) Row { Text("正直ボーナス", Modifier.weight(1f)); Text("+10%") }
            if (saved.themeMatch) Row { Text("週のお題", Modifier.weight(1f)); Text("+20%") }
            if (saved.elapsedFraction < 1.0) Row { Text("中断", Modifier.weight(1f)); Text("×${"%.2f".format(saved.elapsedFraction)}") }
            if (saved.result != LessonResult.SUCCESS) Row { Text(saved.result.label, Modifier.weight(1f)); Text("×${saved.result.gainMultiplier}") }
            Row { Text("体力", Modifier.weight(1f)); Text("-${saved.staminaCost}", color = MaterialTheme.colorScheme.error) }
        }
        if (saved.result == LessonResult.FAILED) {
            Spacer(Modifier.height(8.dp))
            Text("正直に言えてえらい。次のレッスンは +10%", color = StaminaGreen)
        }
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onHome, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("ホームへ") }
    }
}
