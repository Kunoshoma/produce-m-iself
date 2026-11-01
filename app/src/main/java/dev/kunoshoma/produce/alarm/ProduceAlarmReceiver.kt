package dev.kunoshoma.produce.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.kunoshoma.produce.domain.ProduceRepository
import dev.kunoshoma.produce.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ProduceAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var notifications: Notifications
    @Inject lateinit var repository: ProduceRepository

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_LESSON_END -> notifications.lessonFinished()
            ACTION_REST_END -> {
                // 時間切れの休むをここで閉じて体力を回復させる（アプリを開き直すまで放置しない）。
                // endRest は休む中の通知を消すので、閉じたあとに「休憩おわり」を出す。
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runCatching { repository.endRest() }.onFailure { Log.w(TAG, "endRest failed", it) }
                        notifications.restFinished()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ProduceAlarmReceiver"
        const val ACTION_LESSON_END = "dev.kunoshoma.produce.LESSON_END"
        const val ACTION_REST_END = "dev.kunoshoma.produce.REST_END"
    }
}
