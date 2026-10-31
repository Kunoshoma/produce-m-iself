package dev.kunoshoma.produce.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.core.model.SleepBucket
import dev.kunoshoma.produce.domain.HomeState
import dev.kunoshoma.produce.domain.ProduceRepository
import dev.kunoshoma.produce.report.ReportService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: ProduceRepository,
    private val reports: ReportService,
) : ViewModel() {

    val state: StateFlow<HomeState?> = repo.homeState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            repo.seedIfEmpty()
            repo.ensureToday()
            reports.backfill(notify = true)
        }
    }

    fun closeDay() = viewModelScope.launch {
        repo.closeDay()
        reports.generateDaily(repo.today(), force = true)
    }
    fun reopenDay() = viewModelScope.launch { repo.reopenDay() }
    fun setOffDay(off: Boolean) = viewModelScope.launch { repo.setOffDay(off) }
    fun reportSleep(bucket: SleepBucket) = viewModelScope.launch { repo.reportSleep(bucket) }
    fun endRest() = viewModelScope.launch { repo.endRest() }
    fun deleteLog(id: Long) = viewModelScope.launch { repo.deleteLog(id) }
}
