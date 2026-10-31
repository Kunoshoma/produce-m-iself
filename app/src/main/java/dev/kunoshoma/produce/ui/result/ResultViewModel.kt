package dev.kunoshoma.produce.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.data.db.ActionLogEntity
import dev.kunoshoma.produce.data.db.ActiveLessonEntity
import dev.kunoshoma.produce.domain.ProduceRepository
import dev.kunoshoma.produce.notify.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ResultUiState(
    val loading: Boolean = true,
    val lesson: ActiveLessonEntity? = null,
    val result: LessonResult? = null,
    val reasons: Set<FailReason> = emptySet(),
    val note: String = "",
    val saved: ActionLogEntity? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val repo: ProduceRepository,
    private val notifications: Notifications,
) : ViewModel() {

    private val _state = MutableStateFlow(ResultUiState())
    val state: StateFlow<ResultUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val lesson = repo.activeLesson()
            _state.value = _state.value.copy(loading = false, lesson = lesson, result = defaultResult(lesson))
            notifications.cancelLesson()
        }
    }

    private fun defaultResult(lesson: ActiveLessonEntity?): LessonResult =
        if ((lesson?.derailCount ?: 0) > 0) LessonResult.PARTIAL else LessonResult.SUCCESS

    fun setResult(r: LessonResult) { _state.value = _state.value.copy(result = r) }
    fun toggleReason(r: FailReason) {
        val cur = _state.value.reasons
        _state.value = _state.value.copy(reasons = if (r in cur) cur - r else cur + r)
    }
    fun setNote(n: String) { _state.value = _state.value.copy(note = n) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val result = s.result ?: return@launch
        if (s.saving || s.lesson == null) return@launch
        _state.value = s.copy(saving = true)
        val reasons = if (result == LessonResult.SUCCESS) emptySet() else s.reasons
        val saved = repo.finishLesson(result, reasons, s.note.trim())
        _state.value = _state.value.copy(saving = false, saved = saved)
    }
}
