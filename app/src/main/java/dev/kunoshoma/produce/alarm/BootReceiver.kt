package dev.kunoshoma.produce.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.kunoshoma.produce.domain.ProduceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 再起動後、進行中のレッスンがあれば終了アラームを張り直す。 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: ProduceRepository
    @Inject lateinit var alarms: ProduceAlarms

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.activeLesson()?.let { alarms.scheduleLessonEnd(it.plannedEndAt) }
            } finally {
                pending.finish()
            }
        }
    }
}
