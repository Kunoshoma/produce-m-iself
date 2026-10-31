package dev.kunoshoma.produce

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.notify.Notifications
import dev.kunoshoma.produce.report.ReportWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ProduceApp : Application() {
    @Inject lateinit var notifications: Notifications
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
        scope.launch { ReportWorker.schedule(this@ProduceApp, settings.current().dayBoundaryHour) }
    }
}
