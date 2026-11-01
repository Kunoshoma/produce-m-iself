package dev.kunoshoma.produce.interrupt

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat
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
 *
 * 通知は「動いていたのに止まった」ときに 1 回、止まったままなら 6 時間ごとに 1 回まで。
 * 一度も接続されたことがない（初回セットアップ前）なら、停止扱いにも通知にもしない。
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
        if (SettingsViewModel.isAccessibilityEnabled(applicationContext)) {
            deps.repo().markServiceConnected()
            noteServiceConnected(applicationContext)
            return Result.success()
        }
        val prefs = prefs(applicationContext)
        if (!prefs.getBoolean(KEY_EVER_CONNECTED, false)) return Result.success()
        val opened = deps.repo().markServiceDisconnected()
        val now = System.currentTimeMillis()
        val lastAlertAt = prefs.getLong(KEY_LAST_ALERT_AT, 0L)
        if (opened || now - lastAlertAt >= REALERT_INTERVAL_MILLIS) {
            deps.notifications().serviceDown()
            prefs.edit().putLong(KEY_LAST_ALERT_AT, now).apply()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "service_watchdog"
        private const val PREFS = "watchdog"
        private const val KEY_EVER_CONNECTED = "ever_connected"
        private const val KEY_LAST_ALERT_AT = "last_alert_at"
        private const val REALERT_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        /**
         * サービスが接続された（または OS 設定で有効と確認できた）。
         * 初回接続を記録し、停止通知の抑制をリセットし、出ている「止まってるよ」通知を消す。
         */
        fun noteServiceConnected(context: Context) {
            prefs(context).edit()
                .putBoolean(KEY_EVER_CONNECTED, true)
                .remove(KEY_LAST_ALERT_AT)
                .apply()
            NotificationManagerCompat.from(context).cancel(Notifications.ID_SERVICE)
        }

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
