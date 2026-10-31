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

    fun cancelLesson() = NotificationManagerCompat.from(context).cancel(ID_LESSON)
    fun cancelRest() = NotificationManagerCompat.from(context).cancel(ID_REST)

    private fun post(id: Int, title: String, text: String, route: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_LESSON)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    companion object {
        const val CHANNEL_LESSON = "lesson"
        const val CHANNEL_REPORT = "report"
        const val ID_LESSON = 1
        const val ID_REST = 2
    }
}
