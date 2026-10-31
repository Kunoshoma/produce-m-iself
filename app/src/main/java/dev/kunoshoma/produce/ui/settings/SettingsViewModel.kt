package dev.kunoshoma.produce.ui.settings

import android.content.Context
import android.provider.Settings as SystemSettings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kunoshoma.produce.data.settings.Settings
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
) : ViewModel() {
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
