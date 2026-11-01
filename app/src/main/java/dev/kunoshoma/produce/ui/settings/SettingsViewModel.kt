package dev.kunoshoma.produce.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kunoshoma.produce.backup.BackupManager
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.interrupt.InterruptAccessibilityService
import dev.kunoshoma.produce.report.ReportWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val backup: BackupManager,
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        _message.value = runCatching { backup.exportTo(uri); "バックアップを書き出した" }.getOrElse { "書き出し失敗：${it.message}" }
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        val before = runCatching { repo.current().dayBoundaryHour }.getOrNull()
        _message.value = runCatching { backup.importFrom(uri); "復元した" }.getOrElse { "復元失敗：${it.message}" }
        val after = runCatching { repo.current().dayBoundaryHour }.getOrNull()
        if (after != null && after != before) rescheduleBoundary(after)
    }

    val settings: StateFlow<Settings?> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** アクセシビリティ設定でこのアプリのサービスが有効か。設定画面から戻ったときに反映されるよう 2 秒ごとに見る。 */
    val accessibilityEnabled: StateFlow<Boolean> = flow {
        while (true) {
            emit(isAccessibilityEnabled(context))
            delay(2_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 設定を変更する。変換が例外を投げたら（範囲外の値など）保存しない。
     * 日付境界の時刻が変わったら、境界直後のレポート生成ジョブを新しい時刻で張り直す。
     */
    fun update(block: (Settings) -> Settings) = viewModelScope.launch {
        var previousHour: Int? = null
        val next = runCatching {
            repo.update { s ->
                previousHour = s.dayBoundaryHour
                runCatching { block(s) }.getOrDefault(s)
            }
        }.getOrNull() ?: return@launch
        if (previousHour != null && next.dayBoundaryHour != previousHour) rescheduleBoundary(next.dayBoundaryHour)
    }

    private fun rescheduleBoundary(hour: Int) {
        runCatching { ReportWorker.scheduleNextBoundary(context, hour, ExistingWorkPolicy.REPLACE) }
    }

    companion object {
        fun isAccessibilityEnabled(context: Context): Boolean {
            val enabled = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${InterruptAccessibilityService::class.java.name}"
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
