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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    /** 保存・削除の処理中。二度押しで 2 件入らないよう、ボタンはこの間無効。 */
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
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

    /** 編集対象の元の行。sortOrder / deleted / createdAt など画面に出さない列を保存時に引き継ぐ。 */
    private var loaded: ActionTemplateEntity? = null

    init {
        viewModelScope.launch {
            if (id != 0L) {
                repo.template(id)?.let { t ->
                    loaded = t
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

    fun save() {
        val s = _state.value
        if (!s.valid || s.saving || s.saved) return
        // 最初の suspend の前にフラグを立てる（二度押しで新規テンプレが 2 件入らないように）
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            val base = loaded
            val entity = if (base != null) {
                // 元の行を copy して、並び順や論理削除フラグをリセットしない
                base.copy(
                    name = s.name.trim(), axis = s.axis, durationMin = s.durationMin, staminaCost = s.staminaCost,
                    baseGain = s.baseGain, weight = s.weight, windows = s.windows, enabled = s.enabled,
                )
            } else {
                // 新規は末尾に（一覧は axis, sortOrder, id の順）
                val nextOrder = (repo.observeTemplates().first().maxOfOrNull { it.sortOrder } ?: -1) + 1
                ActionTemplateEntity(
                    name = s.name.trim(), axis = s.axis, durationMin = s.durationMin, staminaCost = s.staminaCost,
                    baseGain = s.baseGain, weight = s.weight, windows = s.windows, enabled = s.enabled,
                    sortOrder = nextOrder, createdAt = repo.now(),
                )
            }
            runCatching { repo.saveTemplate(entity) }
                .onSuccess { _state.value = _state.value.copy(saved = true) }
                .onFailure { e -> _state.value = _state.value.copy(saving = false, error = e.message ?: "保存できなかった") }
        }
    }

    fun delete() {
        val s = _state.value
        if (s.saving || s.saved) return
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { if (id != 0L) repo.deleteTemplate(id) }
                .onSuccess { _state.value = _state.value.copy(saved = true) }
                .onFailure { e -> _state.value = _state.value.copy(saving = false, error = e.message ?: "削除できなかった") }
        }
    }
}
