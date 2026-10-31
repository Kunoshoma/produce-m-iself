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
        val result = repo.generateChoices(redraw)
        if (result.isNotEmpty()) _choices.value = result
    }

    fun pickLesson(choice: Choice.Lesson) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        repo.startLesson(choice.template.id, choice.isSp)
            .onSuccess { _event.value = ProduceEvent.LessonStarted }
            .onFailure { _event.value = ProduceEvent.Error(it.message ?: "開始できなかった") }
        _busy.value = false
    }

    fun pickRest(minutes: Int) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        repo.startRest(minutes)
        _event.value = ProduceEvent.Done
        _busy.value = false
    }

    fun pickSleep() = viewModelScope.launch {
        repo.closeDay()
        _event.value = ProduceEvent.Done
    }

    fun consumeEvent() { _event.value = null }
}
