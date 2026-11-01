package dev.kunoshoma.produce.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.data.db.ActionTemplateEntity
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.domain.ProduceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit
import javax.inject.Inject

enum class ManualTab { LESSON, REST }

data class ManualState(
    val tab: ManualTab = ManualTab.LESSON,
    val templateId: Long? = null,
    val minutesAgo: Int = 0,
    val result: LessonResult = LessonResult.SUCCESS,
    val reasons: Set<FailReason> = emptySet(),
    val note: String = "",
    val restMinutes: Int = 30,
    val message: String? = null,
    /** 書き込み中。二度押しで 2 件記録されないよう、ボタンはこの間無効。 */
    val saving: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class ManualLogViewModel @Inject constructor(private val repo: ProduceRepository) : ViewModel() {
    val templates: StateFlow<List<ActionTemplateEntity>> =
        repo.observeTemplates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<Settings?> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(ManualState())
    val state: StateFlow<ManualState> = _state.asStateFlow()

    fun update(block: (ManualState) -> ManualState) { _state.value = block(_state.value) }
    fun toggleReason(r: FailReason) = update { it.copy(reasons = if (r in it.reasons) it.reasons - r else it.reasons + r) }

    fun save() {
        val s = _state.value
        if (s.saving || s.done) return
        // 最初の suspend の前にフラグを立てる（連打で同じ記録が 2 件入らないように）
        _state.value = s.copy(saving = true, message = null)
        viewModelScope.launch {
            val startedAt = repo.now().minus(s.minutesAgo.toLong(), ChronoUnit.MINUTES)
            val outcome: Result<String> = when (s.tab) {
                ManualTab.LESSON -> {
                    val id = s.templateId
                    if (id == null) {
                        Result.failure<String>(IllegalStateException("アクションを選んで"))
                    } else {
                        repo.logManualLesson(id, startedAt, s.result, if (s.result == LessonResult.SUCCESS) emptySet() else s.reasons, s.note.trim())
                            .map { log -> "記録した：${log.templateName} +${log.gain}" }
                    }
                }
                ManualTab.REST -> runCatching { repo.logManualRest(startedAt, s.restMinutes) }
                    .map { rest -> "娯楽 ${rest.actualMin} 分を記録。体力 +${rest.staminaRecovered}" }
            }
            outcome
                .onSuccess { msg -> update { it.copy(saving = false, done = true, message = msg) } }
                .onFailure { e -> update { it.copy(saving = false, message = e.message ?: "保存できなかった") } }
        }
    }

    fun consumeMessage() = update { it.copy(message = null) }
}
