package dev.kunoshoma.produce

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.interrupt.ServiceWatchdogWorker
import dev.kunoshoma.produce.notify.Notifications
import dev.kunoshoma.produce.report.ReportWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ProduceApp : Application() {
    @Inject lateinit var notifications: Notifications
    @Inject lateinit var settings: SettingsRepository

    /** 起動時の雑務が失敗してもプロセスを落とさない（設定が読めなくても既定値で予約する）。 */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "startup task failed", e) },
    )

    override fun onCreate() {
        super.onCreate()
        runCatching { notifications.ensureChannels() }.onFailure { Log.e(TAG, "notification channels", it) }
        scope.launch {
            val hour = runCatching { settings.current().dayBoundaryHour }
                .onFailure { Log.w(TAG, "settings unreadable, using default day boundary", it) }
                .getOrDefault(Settings().dayBoundaryHour)
            ReportWorker.schedule(this@ProduceApp, hour)
        }
        runCatching { ServiceWatchdogWorker.schedule(this) }.onFailure { Log.e(TAG, "watchdog schedule", it) }
    }

    private companion object {
        const val TAG = "ProduceApp"
    }
}
