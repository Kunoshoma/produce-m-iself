package dev.kunoshoma.produce.report

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.kunoshoma.produce.data.settings.SettingsRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * レポートの定期生成（SPEC 5.3）。Hilt の Worker 連携を使わず EntryPoint 経由で依存を取る。
 * 周期ジョブ（6 時間ごと）＋ 日付境界直後の 1 回ジョブで、落としても起動時のバックフィルで埋まる。
 */
class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun reportService(): ReportService
        fun settings(): SettingsRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        deps.reportService().backfill(notify = true)
        scheduleNextBoundary(applicationContext, deps.settings().current().dayBoundaryHour)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "report_periodic"
        private const val BOUNDARY = "report_boundary"

        fun schedule(context: Context, dayBoundaryHour: Int) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReportWorker>(6, TimeUnit.HOURS).build(),
            )
            scheduleNextBoundary(context, dayBoundaryHour)
        }

        /** 次の日付境界 + 2 分に 1 回だけ走らせる。 */
        fun scheduleNextBoundary(context: Context, dayBoundaryHour: Int) {
            val zone = ZoneId.systemDefault()
            val now = Instant.now().atZone(zone)
            var next = now.toLocalDate().atTime(LocalTime.of(dayBoundaryHour, 2)).atZone(zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)
            WorkManager.getInstance(context).enqueueUniqueWork(
                BOUNDARY,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReportWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build(),
            )
        }
    }
}
