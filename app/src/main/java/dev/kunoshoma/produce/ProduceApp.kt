package dev.kunoshoma.produce

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.kunoshoma.produce.notify.Notifications
import javax.inject.Inject

@HiltAndroidApp
class ProduceApp : Application() {
    @Inject lateinit var notifications: Notifications

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }
}
