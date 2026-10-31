package dev.kunoshoma.produce.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.kunoshoma.produce.notify.Notifications
import javax.inject.Inject

@AndroidEntryPoint
class ProduceAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var notifications: Notifications

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_LESSON_END -> notifications.lessonFinished()
            ACTION_REST_END -> notifications.restFinished()
        }
    }

    companion object {
        const val ACTION_LESSON_END = "dev.kunoshoma.produce.LESSON_END"
        const val ACTION_REST_END = "dev.kunoshoma.produce.REST_END"
    }
}
