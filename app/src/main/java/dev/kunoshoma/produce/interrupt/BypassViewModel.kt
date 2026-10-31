package dev.kunoshoma.produce.interrupt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.domain.ProduceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BypassState { Idle, Granted, Denied }

@HiltViewModel
class BypassViewModel @Inject constructor(private val repo: ProduceRepository) : ViewModel() {
    private val _state = MutableStateFlow(BypassState.Idle)
    val state: StateFlow<BypassState> = _state.asStateFlow()

    fun requestBypass(packageName: String?) = viewModelScope.launch {
        _state.value = if (repo.startBypass(packageName) != null) BypassState.Granted else BypassState.Denied
    }

    fun reset() { _state.value = BypassState.Idle }
}
