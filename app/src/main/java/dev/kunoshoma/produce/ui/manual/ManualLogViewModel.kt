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

    fun save() = viewModelScope.launch {
        val s = _state.value
        val startedAt = repo.now().minus(s.minutesAgo.toLong(), ChronoUnit.MINUTES)
        when (s.tab) {
            ManualTab.LESSON -> {
                val id = s.templateId ?: run { update { it.copy(message = "アクションを選んで") }; return@launch }
                repo.logManualLesson(id, startedAt, s.result, if (s.result == LessonResult.SUCCESS) emptySet() else s.reasons, s.note.trim())
                    .onSuccess { log -> update { it.copy(done = true, message = "記録した：${log.templateName} +${log.gain}") } }
                    .onFailure { e -> update { it.copy(message = e.message ?: "保存できなかった") } }
            }
            ManualTab.REST -> {
                val rest = repo.logManualRest(startedAt, s.restMinutes)
                update { it.copy(done = true, message = "娯楽 ${rest.actualMin} 分を記録。体力 +${rest.staminaRecovered}") }
            }
        }
    }

    fun consumeMessage() = update { it.copy(message = null) }
}
