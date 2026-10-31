package dev.kunoshoma.produce.ui.actions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.DayPart
import dev.kunoshoma.produce.core.preset.Presets
import dev.kunoshoma.produce.data.db.ActionTemplateEntity
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.domain.ProduceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class ActionsViewModel @Inject constructor(private val repo: ProduceRepository) : ViewModel() {
    val templates: StateFlow<List<ActionTemplateEntity>> =
        repo.observeTemplates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<Settings?> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { repo.setTemplateEnabled(id, enabled) }
}

data class ActionEditState(
    val id: Long = 0,
    val name: String = "",
    val axis: Axis = Axis.HEALTH,
    val durationMin: Int = 30,
    val staminaCost: Int = 20,
    val baseGain: Int = 12,
    val weight: Int = 3,
    val windows: Set<DayPart> = emptySet(),
    val enabled: Boolean = true,
    val loaded: Boolean = false,
    val saved: Boolean = false,
) {
    val valid: Boolean get() = name.isNotBlank() && durationMin > 0 && staminaCost >= 0 && baseGain >= 0
}

@HiltViewModel
class ActionEditViewModel @Inject constructor(
    private val repo: ProduceRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val id: Long = savedState.get<Long>("id") ?: 0L
    private val _state = MutableStateFlow(ActionEditState(id = id))
    val state: StateFlow<ActionEditState> = _state.asStateFlow()
    private var createdAt: Instant? = null

    init {
        viewModelScope.launch {
            if (id != 0L) {
                repo.template(id)?.let { t ->
                    createdAt = t.createdAt
                    _state.value = ActionEditState(
                        id = t.id, name = t.name, axis = t.axis, durationMin = t.durationMin, staminaCost = t.staminaCost,
                        baseGain = t.baseGain, weight = t.weight, windows = t.windows, enabled = t.enabled, loaded = true,
                    )
                    return@launch
                }
            }
            _state.value = _state.value.copy(loaded = true)
        }
    }

    fun update(block: (ActionEditState) -> ActionEditState) { _state.value = block(_state.value) }

    /** 時間を変えたらコスト・獲得の目安を自動で入れる。 */
    fun setDuration(min: Int) {
        val (cost, gain) = Presets.suggestCostAndGain(min)
        update { it.copy(durationMin = min, staminaCost = cost, baseGain = gain) }
    }

    fun toggleWindow(p: DayPart) = update { it.copy(windows = if (p in it.windows) it.windows - p else it.windows + p) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (!s.valid) return@launch
        repo.saveTemplate(
            ActionTemplateEntity(
                id = s.id, name = s.name.trim(), axis = s.axis, durationMin = s.durationMin, staminaCost = s.staminaCost,
                baseGain = s.baseGain, weight = s.weight, windows = s.windows, enabled = s.enabled,
                createdAt = createdAt ?: Instant.now(),
            ),
        )
        _state.value = s.copy(saved = true)
    }

    fun delete() = viewModelScope.launch {
        if (id != 0L) repo.deleteTemplate(id)
        _state.value = _state.value.copy(saved = true)
    }
}
