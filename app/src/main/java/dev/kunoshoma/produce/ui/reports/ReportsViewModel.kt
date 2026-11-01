package dev.kunoshoma.produce.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.report.DailyReport
import dev.kunoshoma.produce.core.report.WeeklyReport
import dev.kunoshoma.produce.data.db.DailyReportEntity
import dev.kunoshoma.produce.data.db.WeeklyReportEntity
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.report.ReportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 保存済み JSON が今のスキーマと合わないとき（古いビルドの行・壊れた行）に出す文言。落とさずに表示で伝える。 */
internal const val REPORT_LOAD_ERROR = "このレポートを読み込めなかった。古いバージョンで作った分かもしれない。"

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reports: ReportService,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val daily: StateFlow<List<DailyReportEntity>> = reports.observeDaily().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val weekly: StateFlow<List<WeeklyReportEntity>> = reports.observeWeekly().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<Settings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _trend = MutableStateFlow<Map<LocalDate, Map<Axis, Int>>>(emptyMap())
    val trend: StateFlow<Map<LocalDate, Map<Axis, Int>>> = _trend.asStateFlow()

    init {
        viewModelScope.launch {
            reports.backfill(notify = false)
            _trend.value = reports.dailyGains(30)
        }
    }

    fun generateToday() = viewModelScope.launch {
        _trend.value = reports.dailyGains(30)
    }
}

@HiltViewModel
class DailyReportViewModel @Inject constructor(
    reports: ReportService,
    settingsRepo: SettingsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val _report = MutableStateFlow<DailyReport?>(null)
    val report: StateFlow<DailyReport?> = _report.asStateFlow()
    val settings: StateFlow<Settings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    /** 読み込みに失敗したときの文言。null なら正常。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            val date = savedState.get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            // JSON のデコード失敗（SerializationException / IllegalArgumentException など）で落とさず、エラー表示にする
            _report.value = date?.let { d ->
                try {
                    reports.daily(d)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = REPORT_LOAD_ERROR
                    null
                }
            }
            _loaded.value = true
        }
    }
}

@HiltViewModel
class WeeklyReportViewModel @Inject constructor(
    reports: ReportService,
    settingsRepo: SettingsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val _report = MutableStateFlow<WeeklyReport?>(null)
    val report: StateFlow<WeeklyReport?> = _report.asStateFlow()
    val settings: StateFlow<Settings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    /** 読み込みに失敗したときの文言。null なら正常。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            val week = savedState.get<String>("weekStart")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            _report.value = week?.let { w ->
                try {
                    reports.weekly(w)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = REPORT_LOAD_ERROR
                    null
                }
            }
            _loaded.value = true
        }
    }
}
