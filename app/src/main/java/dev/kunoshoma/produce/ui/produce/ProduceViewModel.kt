package dev.kunoshoma.produce.ui.produce

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.core.rules.Choice
import dev.kunoshoma.produce.domain.HomeState
import dev.kunoshoma.produce.domain.ProduceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProduceEvent {
    data object LessonStarted : ProduceEvent
    data object Done : ProduceEvent
    data class Error(val message: String) : ProduceEvent
}

@HiltViewModel
class ProduceViewModel @Inject constructor(private val repo: ProduceRepository) : ViewModel() {

    val home: StateFlow<HomeState?> = repo.homeState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _choices = MutableStateFlow<List<Choice>>(emptyList())
    val choices: StateFlow<List<Choice>> = _choices.asStateFlow()

    private val _event = MutableStateFlow<ProduceEvent?>(null)
    val event: StateFlow<ProduceEvent?> = _event.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        draw(redraw = false)
    }

    fun draw(redraw: Boolean) = viewModelScope.launch {
        // 連打で引き直し回数を 2 回分消費しないよう、処理中は受け付けない
        if (_busy.value) return@launch
        _busy.value = true
        try {
            val result = repo.generateChoices(redraw)
            if (result.isNotEmpty()) _choices.value = result
        } finally {
            _busy.value = false
        }
    }

    fun pickLesson(choice: Choice.Lesson) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        repo.startLesson(choice.template.id, choice.isSp)
            .onSuccess { _event.value = ProduceEvent.LessonStarted }
            .onFailure { _event.value = ProduceEvent.Error(it.message ?: "開始できなかった") }
        _busy.value = false
    }

    /** 直前の Done がレッスン開始でも休むでもなく「寝る」だったか。割り込み画面の遷移先判定用。 */
    var lastPickWasSleep: Boolean = false
        private set

    fun pickRest(minutes: Int, packageName: String? = null) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        lastPickWasSleep = false
        repo.startRest(minutes, packageName)
            .onSuccess { _event.value = ProduceEvent.Done }
            .onFailure { _event.value = ProduceEvent.Error(it.message ?: "休めなかった") }
        _busy.value = false
    }

    /** 「寝る」：日を閉じて、その場で日次レポートを作る（ホームの「寝る」と同じ処理）。 */
    fun pickSleep() = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        lastPickWasSleep = true
        repo.closeDayAndReport()
        _event.value = ProduceEvent.Done
        _busy.value = false
    }

    fun consumeEvent() { _event.value = null }
}
