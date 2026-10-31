package dev.kunoshoma.produce.interrupt

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.domain.ProduceRepository
import dev.kunoshoma.produce.notify.Notifications
import dev.kunoshoma.produce.ui.settings.SettingsViewModel
import java.util.concurrent.TimeUnit

/**
 * 割り込みサービスの死活監視（SPEC 13.5）。
 * 「アプリ内では割り込み ON なのに、OS 側でアクセシビリティが切れている」状態を 15 分ごとに見て通知する。
 * サービスが切れている間は service_gaps に停止区間として記録される。
 */
class ServiceWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun repo(): ProduceRepository
        fun settings(): SettingsRepository
        fun notifications(): Notifications
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val settings = deps.settings().current()
        if (!settings.interruptEnabled) return Result.success()
        val enabled = SettingsViewModel.isAccessibilityEnabled(applicationContext)
        if (enabled) {
            deps.repo().markServiceConnected()
        } else {
            deps.repo().markServiceDisconnected()
            deps.notifications().serviceDown()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "service_watchdog"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
