package dev.kunoshoma.produce.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.kunoshoma.produce.data.db.RestLogDao
import dev.kunoshoma.produce.domain.ProduceRepository
import dev.kunoshoma.produce.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** 再起動後、進行中のレッスン・休むがあれば終了アラームを張り直す（AlarmManager の予約は再起動で消える）。 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: ProduceRepository
    @Inject lateinit var rests: RestLogDao
    @Inject lateinit var alarms: ProduceAlarms
    @Inject lateinit var notifications: Notifications

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    repository.activeLesson()?.let { lesson ->
                        // 通知は再起動で消えるので、まだ終わっていなければカウントダウンを出し直してからアラームを張る
                        // （終了済みならアラームが即時に鳴って「レッスン終了！」が同じ ID で出るので、ここでは出さない）
                        if (lesson.plannedEndAt.isAfter(Instant.now())) notifications.lessonRunning(lesson.templateName, lesson.plannedEndAt)
                        alarms.scheduleLessonEnd(lesson.plannedEndAt)
                    }
                }.onFailure { Log.w(TAG, "re-arm lesson alarm failed", it) }
                // 休むは開始時刻 + 許可時間。すでに過ぎていればアラームは即時に鳴り、受信側で休むを閉じる
                runCatching {
                    rests.openRest()?.let { open ->
                        alarms.scheduleRestEnd(open.startedAt.plus(open.allowedMin.toLong(), ChronoUnit.MINUTES))
                    }
                }.onFailure { Log.w(TAG, "re-arm rest alarm failed", it) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
