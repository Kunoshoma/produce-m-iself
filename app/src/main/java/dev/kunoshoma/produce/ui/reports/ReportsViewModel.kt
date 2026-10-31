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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

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

    init {
        viewModelScope.launch {
            val date = savedState.get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            _report.value = date?.let { reports.daily(it) }
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

    init {
        viewModelScope.launch {
            val week = savedState.get<String>("weekStart")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            _report.value = week?.let { reports.weekly(it) }
            _loaded.value = true
        }
    }
}
