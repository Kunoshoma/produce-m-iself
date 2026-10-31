package dev.kunoshoma.produce.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** レッスン終了・休む終了の通知を AlarmManager で予約する。 */
@Singleton
class ProduceAlarms @Inject constructor(@ApplicationContext private val context: Context) {

    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun scheduleLessonEnd(at: Instant) = schedule(ProduceAlarmReceiver.ACTION_LESSON_END, REQ_LESSON, at)
    fun cancelLessonEnd() = cancel(ProduceAlarmReceiver.ACTION_LESSON_END, REQ_LESSON)
    fun scheduleRestEnd(at: Instant) = schedule(ProduceAlarmReceiver.ACTION_REST_END, REQ_REST, at)
    fun cancelRestEnd() = cancel(ProduceAlarmReceiver.ACTION_REST_END, REQ_REST)

    private fun pending(action: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, req,
            Intent(context, ProduceAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun schedule(action: String, req: Int, at: Instant) {
        val pi = pending(action, req)
        val am = alarmManager
        val millis = at.toEpochMilli()
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun cancel(action: String, req: Int) {
        alarmManager.cancel(pending(action, req))
    }

    private companion object {
        const val REQ_LESSON = 1001
        const val REQ_REST = 1002
    }
}
