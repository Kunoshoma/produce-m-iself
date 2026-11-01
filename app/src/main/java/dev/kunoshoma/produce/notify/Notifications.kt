package dev.kunoshoma.produce.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kunoshoma.produce.R
import dev.kunoshoma.produce.ui.MainActivity
import dev.kunoshoma.produce.ui.Routes
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifications @Inject constructor(@ApplicationContext private val context: Context) {

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LESSON, "レッスン・休む", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "レッスンや休む時間の終了を知らせます"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REPORT, "レポート", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "日次・週次レポート"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "レッスン中の残り時間", NotificationManager.IMPORTANCE_LOW).apply {
                description = "レッスン中に残り時間を表示します（音は鳴りません）"
                setShowBadge(false)
            },
        )
    }

    /** レッスン中の常駐通知（SPEC 3.5）。残り時間をカウントダウンで表示し、タップでタイマー画面へ。 */
    fun lessonRunning(templateName: String, plannedEndAt: Instant) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("レッスン中：$templateName")
            .setContentText("終了まで")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(plannedEndAt.toEpochMilli())
            .setContentIntent(contentIntent(ID_LESSON, Routes.LESSON))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        NotificationManagerCompat.from(context).notify(ID_LESSON, n)
    }

    fun lessonFinished() = post(
        id = ID_LESSON,
        title = "レッスン終了！",
        text = "お疲れさま。結果を記録しよう",
        route = MainActivity.ROUTE_RESULT,
    )

    fun restFinished() = post(
        id = ID_REST,
        title = "休憩おわり",
        text = "次はどうする？ 3 択を引こう",
        route = MainActivity.ROUTE_PRODUCE,
    )

    fun dailyReport(dateLabel: String, totalGain: Int, comment: String) = post(
        id = ID_DAILY,
        title = "日次レポート：$dateLabel  +$totalGain",
        text = comment,
        route = MainActivity.ROUTE_REPORTS,
        channel = CHANNEL_REPORT,
    )

    fun weeklyReport(rank: String, total: Int, comment: String) = post(
        id = ID_WEEKLY,
        title = "週次レポート：ランク $rank（$total pt）",
        text = comment,
        route = MainActivity.ROUTE_REPORTS,
        channel = CHANNEL_REPORT,
    )

    fun exam(title: String, text: String) = post(
        id = ID_EXAM,
        title = title,
        text = text,
        route = MainActivity.ROUTE_GOAL,
        channel = CHANNEL_REPORT,
    )

    /** 表示中なら音を鳴らさずに更新する（15 分ごとの死活監視で鳴り続けないように）。 */
    fun serviceDown() = post(
        id = ID_SERVICE,
        title = "割り込みが止まってるよ",
        text = "アクセシビリティ設定で「プロデュース」を再度 ON にして。止まってた時間は「監視なし」として記録される",
        route = MainActivity.ROUTE_SETTINGS,
        channel = CHANNEL_REPORT,
        onlyAlertOnce = true,
    )

    fun cancelServiceDown() = NotificationManagerCompat.from(context).cancel(ID_SERVICE)

    fun cancelLesson() = NotificationManagerCompat.from(context).cancel(ID_LESSON)
    fun cancelRest() = NotificationManagerCompat.from(context).cancel(ID_REST)

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun contentIntent(id: Int, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(id: Int, title: String, text: String, route: String, channel: String = CHANNEL_LESSON, onlyAlertOnce: Boolean = false) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(onlyAlertOnce)
            .setContentIntent(contentIntent(id, route))
            .setAutoCancel(true)
            .setPriority(if (channel == CHANNEL_LESSON) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (channel == CHANNEL_LESSON) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    companion object {
        const val CHANNEL_LESSON = "lesson"
        const val CHANNEL_REPORT = "report"
        const val CHANNEL_PROGRESS = "lesson_progress"
        const val ID_LESSON = 1
        const val ID_REST = 2
        const val ID_DAILY = 3
        const val ID_WEEKLY = 4
        const val ID_SERVICE = 5
        const val ID_EXAM = 6
    }
}
