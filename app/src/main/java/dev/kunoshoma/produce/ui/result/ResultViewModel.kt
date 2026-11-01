package dev.kunoshoma.produce.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.data.db.ActionLogEntity
import dev.kunoshoma.produce.data.db.ActiveLessonEntity
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.domain.ProduceRepository
import dev.kunoshoma.produce.notify.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class ResultUiState(
    val loading: Boolean = true,
    val lesson: ActiveLessonEntity? = null,
    val result: LessonResult? = null,
    val reasons: Set<FailReason> = emptySet(),
    val note: String = "",
    val saved: ActionLogEntity? = null,
    val saving: Boolean = false,
    /** 保存に失敗したときのメッセージ。再試行できる。 */
    val error: String? = null,
)

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val repo: ProduceRepository,
    private val notifications: Notifications,
) : ViewModel() {

    private val _state = MutableStateFlow(ResultUiState())
    val state: StateFlow<ResultUiState> = _state.asStateFlow()

    /** 軸の表示名（設定で変更可）。null = まだ読めていない。 */
    val settings: StateFlow<Settings?> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * この画面を開いた瞬間 ＝ レッスンを止めた時刻。
     * 「中断」でここに来た場合、自己申告に時間がかかっても獲得の経過比例はこの時刻で計算する（SPEC 3.5）。
     * タイマー完了後に開いた場合は予定終了より後なので、finishLesson 側で予定時間いっぱいに丸められる。
     */
    private val openedAt: Instant = repo.now()

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

    fun save() {
        val s = _state.value
        val result = s.result ?: return
        if (s.saving || s.saved != null || s.lesson == null) return
        // 二度押しで 2 件記録しないよう、最初の suspend の前にフラグを立てる
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            val reasons = if (result == LessonResult.SUCCESS) emptySet() else s.reasons
            // DB 障害などで落ちてもクラッシュさせない。トランザクションは戻るのでレッスンは進行中のまま、もう一度押せる
            runCatching { repo.finishLesson(result, reasons, s.note.trim(), endedAt = openedAt) }
                .onSuccess { saved -> _state.value = _state.value.copy(saving = false, saved = saved) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.value = _state.value.copy(saving = false, error = e.message ?: "保存できなかった")
                }
        }
    }
}
