package dev.kunoshoma.produce.report

import android.content.Context
import android.util.Log
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
import dev.kunoshoma.produce.backup.BackupManager
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * レポートの定期生成（SPEC 5.3）。Hilt の Worker 連携を使わず EntryPoint 経由で依存を取る。
 * 周期ジョブ（6 時間ごと）＋ 日付境界直後の 1 回ジョブで、落としても起動時のバックフィルで埋まる。
 *
 * 境界ジョブ（一意名 [BOUNDARY]）の予約ポリシー：
 * - アプリ起動時・周期ジョブからは KEEP。境界ジョブに起こされたプロセスの Application.onCreate が、
 *   まさに走ろうとしているその境界ジョブを REPLACE で消してしまわないように。
 * - 境界ジョブ自身は次回分を APPEND_OR_REPLACE で自分の後ろに繋ぐ（REPLACE だと実行中の自分を取り消す）。
 * - 設定で日付境界の時刻が変わったときだけ REPLACE（SettingsViewModel）。
 */
class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun reportService(): ReportService
        fun settings(): SettingsRepository
        fun backup(): BackupManager
        fun widget(): WidgetUpdater
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val isBoundaryRun = tags.contains(TAG_BOUNDARY)
        var failure: Throwable? = null
        try {
            try {
                deps.reportService().backfill(notify = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "backfill failed", e)
                failure = e
            }
            // 日付が変わった直後はウィジェットの体力・今日の獲得が古いままなので描き直す（SPEC 13.6）
            runCatching { deps.widget().update() }.onFailure { Log.w(TAG, "widget update failed", it) }
            // 自動バックアップはプロデュース日に 1 回まで（4 世代が数時間で回らないように）
            runCatching { deps.backup().autoBackupIfDue() }.onFailure { Log.w(TAG, "auto backup failed", it) }
        } finally {
            // バックフィルが例外で終わっても次の境界は必ず予約する（鎖を切らない）。
            // ただし自分が止められた（設定変更の REPLACE など）ときは、置き換え後の鎖に二重に繋がないよう何もしない。
            if (!isStopped) {
                val hour = runCatching { deps.settings().current().dayBoundaryHour }.getOrDefault(Settings().dayBoundaryHour)
                val policy = if (isBoundaryRun) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
                runCatching { scheduleNextBoundary(applicationContext, hour, policy) }
                    .onFailure { Log.e(TAG, "schedule next boundary failed", it) }
            }
        }
        // 周期ジョブだけ少し後に再試行する。境界ジョブは次回分を繋いだあとなので再試行で鎖を増やさない
        return if (failure != null && !isBoundaryRun && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "ReportWorker"
        private const val PERIODIC = "report_periodic"
        private const val BOUNDARY = "report_boundary"
        private const val TAG_BOUNDARY = "report_boundary_run"
        private const val MAX_RETRIES = 2

        /** アプリ起動時。すでに予約済み・実行中の境界ジョブは触らない。 */
        fun schedule(context: Context, dayBoundaryHour: Int) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReportWorker>(6, TimeUnit.HOURS).build(),
            )
            scheduleNextBoundary(context, dayBoundaryHour, ExistingWorkPolicy.KEEP)
        }

        /**
         * 次の日付境界 + 2 分に 1 回だけ走らせる。
         * [policy] は既存の境界ジョブの扱い。日付境界の時刻を変えたときは REPLACE で張り直す。
         */
        fun scheduleNextBoundary(
            context: Context,
            dayBoundaryHour: Int,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            val hour = dayBoundaryHour.coerceIn(0, 23)
            val zone = ZoneId.systemDefault()
            val now = Instant.now().atZone(zone)
            var next = now.toLocalDate().atTime(LocalTime.of(hour, 2)).atZone(zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)
            WorkManager.getInstance(context).enqueueUniqueWork(
                BOUNDARY,
                policy,
                OneTimeWorkRequestBuilder<ReportWorker>()
                    .addTag(TAG_BOUNDARY)
                    .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }
}
