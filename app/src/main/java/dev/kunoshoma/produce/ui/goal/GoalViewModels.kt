package dev.kunoshoma.produce.ui.goal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.rules.GoalCalculator
import dev.kunoshoma.produce.data.db.ActionTemplateEntity
import dev.kunoshoma.produce.data.db.ExamResultEntity
import dev.kunoshoma.produce.data.db.GoalStatus
import dev.kunoshoma.produce.data.db.GoalWithTargets
import dev.kunoshoma.produce.data.db.TargetKind
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.domain.ProduceRepository
import dev.kunoshoma.produce.goal.GoalEvaluation
import dev.kunoshoma.produce.goal.GoalService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class GoalUi(val goal: GoalWithTargets, val evaluation: GoalEvaluation?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalViewModel @Inject constructor(
    private val service: GoalService,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<Settings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** null = 読み込み中。Loaded(null) = 目標なし。 */
    val active: StateFlow<Loaded?> = service.observeActive()
        .map { g -> Loaded(g?.let { GoalUi(it, runCatching { service.evaluate(it) }.getOrNull()) }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 進行中の目標の試験（中間）。 */
    val exams: StateFlow<List<ExamResultEntity>> = service.observeActive()
        .flatMapLatest { g -> if (g == null) flowOf(emptyList()) else service.observeExams(g.goal.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<GoalWithTargets>> = service.observeAll()
        .map { list -> list.filter { it.goal.status != GoalStatus.ACTIVE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 終わった目標ごとの試験結果（goalId → 新しい順）。
     * 最終試験で目標が PASSED / FAILED になった瞬間に [exams] は空になるので、
     * 中間・最終の結果は履歴側からも見返せるようにしておく（SPEC 12.5）。
     */
    val historyExams: StateFlow<Map<Long, List<ExamResultEntity>>> = service.observeAll()
        .map { list -> list.filter { it.goal.status != GoalStatus.ACTIVE }.map { it.goal.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids -> examsByGoal(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun examsByGoal(ids: List<Long>): Flow<Map<Long, List<ExamResultEntity>>> {
        if (ids.isEmpty()) return flowOf<Map<Long, List<ExamResultEntity>>>(emptyMap())
        val flows: List<Flow<Pair<Long, List<ExamResultEntity>>>> = ids.map { id -> service.observeExams(id).map { list -> id to list } }
        return combine(flows) { pairs -> pairs.toMap() }
    }

    init {
        viewModelScope.launch { runCatching { service.runFinalExamIfDue() } }
    }

    fun toggleChecklist(targetId: Long) = viewModelScope.launch { service.toggleChecklist(targetId) }
    fun abandon(goalId: Long) = viewModelScope.launch { service.abandon(goalId) }
    fun retry(goalId: Long) = viewModelScope.launch { service.retry(goalId) }
    fun decode(e: ExamResultEntity): GoalEvaluation? = service.decode(e)

    data class Loaded(val ui: GoalUi?)
}

data class DraftTarget(val kind: TargetKind, val ref: String, val label: String, val value: Int, val required: Boolean = true)

data class GoalDraft(
    val name: String = "",
    val weeks: Int = 8,
    val targets: List<DraftTarget> = emptyList(),
    /** 開始日（プロデュース日付）。null = まだ読めていない。 */
    val startDate: LocalDate? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    /**
     * 期限は「開始日 + N 週」。開始日は暦日ではなくプロデュース日付（日付境界 5 時）なので、
     * 深夜 0 時〜境界の間に作っても GoalService.create の startDate と 1 日ずれない。
     */
    val deadline: LocalDate? get() = startDate?.plusWeeks(weeks.toLong())
    val valid: Boolean get() = targets.isNotEmpty() && name.isNotBlank()

    /** 目標画面の daysLeft は今日と期限日を両方含む（deadline - today + 1）ので、作成日時点では N 週 + 1 日。 */
    val days: Int get() = weeks * 7 + 1
}

@HiltViewModel
class GoalCreateViewModel @Inject constructor(
    private val service: GoalService,
    private val repo: ProduceRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<Settings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val templates: StateFlow<List<ActionTemplateEntity>> = repo.observeTemplates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val presets = service.presets()

    private val _draft = MutableStateFlow(GoalDraft())
    val draft: StateFlow<GoalDraft> = _draft.asStateFlow()

    init {
        viewModelScope.launch {
            val today = repo.today()
            update { it.copy(startDate = today) }
        }
    }

    fun update(block: (GoalDraft) -> GoalDraft) { _draft.value = block(_draft.value) }

    fun applyPreset(p: GoalService.GoalPreset) = update {
        it.copy(name = p.name, weeks = p.weeks, targets = p.targets.map { t -> DraftTarget(t.kind, t.ref, t.label, t.targetValue, t.required) })
    }

    fun addAxisTarget(axis: Axis, value: Int, label: String) = update { it.copy(targets = it.targets + DraftTarget(TargetKind.AXIS, axis.name, "$label +$value", value)) }
    fun addActionTarget(t: ActionTemplateEntity, count: Int) = update { it.copy(targets = it.targets + DraftTarget(TargetKind.ACTION_COUNT, t.id.toString(), "${t.name} × $count 回", count)) }
    fun addChecklist(text: String) = update { it.copy(targets = it.targets + DraftTarget(TargetKind.CHECKLIST, text.trim(), text.trim(), 1)) }
    fun removeTarget(index: Int) = update { it.copy(targets = it.targets.filterIndexed { i, _ -> i != index }) }
    fun toggleRequired(index: Int) = update { it.copy(targets = it.targets.mapIndexed { i, t -> if (i == index) t.copy(required = !t.required) else t }) }

    /** 作成前のプレビュー：1 日あたりの必要量（SPEC 12.6）。作成後に目標画面が出す値と同じ日数で割る。 */
    fun perDay(t: DraftTarget): Double = GoalCalculator.evaluate(t.value, 0, 1, _draft.value.days).requiredPerDay

    fun save() {
        val d = _draft.value
        if (!d.valid || d.saving || d.saved) return
        _draft.value = d.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                // 期限は保存時点のプロデュース日付から数える（画面を開いたまま日付境界をまたいでも N 週ちょうど）
                val start = repo.today()
                service.create(
                    name = d.name, deadline = start.plusWeeks(d.weeks.toLong()),
                    targets = d.targets.map { GoalService.NewTarget(it.kind, it.ref, it.label, it.value, it.required) },
                )
            }.onSuccess { _draft.value = _draft.value.copy(saving = false, saved = true) }
                .onFailure { e -> _draft.value = _draft.value.copy(saving = false, error = e.message ?: "作成できなかった") }
        }
    }
}
