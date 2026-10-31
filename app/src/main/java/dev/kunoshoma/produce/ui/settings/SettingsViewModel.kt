package dev.kunoshoma.produce.ui.settings

import android.content.Context
import android.provider.Settings as SystemSettings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.net.Uri
import dev.kunoshoma.produce.backup.BackupManager
import dev.kunoshoma.produce.data.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.interrupt.InterruptAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        _message.value = runCatching { backup.importFrom(uri); "復元した。アプリを再起動すると確実" }.getOrElse { "復元失敗：${it.message}" }
    }

    val settings: StateFlow<Settings?> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** アクセシビリティ設定でこのアプリのサービスが有効か。設定画面から戻ったときに反映されるよう 2 秒ごとに見る。 */
    val accessibilityEnabled: StateFlow<Boolean> = flow {
        while (true) {
            emit(isAccessibilityEnabled(context))
            delay(2_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun update(block: (Settings) -> Settings) = viewModelScope.launch {
        repo.update { s -> runCatching { block(s) }.getOrDefault(s) }
    }

    companion object {
        fun isAccessibilityEnabled(context: Context): Boolean {
            val enabled = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${InterruptAccessibilityService::class.java.name}"
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
