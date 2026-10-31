package dev.kunoshoma.produce.interrupt

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.domain.HomeState
import dev.kunoshoma.produce.domain.ProduceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * 前面アプリの変化を見て、娯楽アプリなら InterruptActivity を出す（SPEC 4.1）。
 * 取得するのはパッケージ名だけ。画面内容は読まない（canRetrieveWindowContent=false）。
 */
@AndroidEntryPoint
class InterruptAccessibilityService : AccessibilityService() {

    @Inject lateinit var repo: ProduceRepository
    @Inject lateinit var settingsRepo: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var settings: Settings? = null
    @Volatile private var home: HomeState? = null

    private var lastLaunchPackage: String? = null
    private var lastLaunchAt: Long = 0
    private var lastDerailAt: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo.observeBlockedPackages().onEach { blocked = it.toSet() }.launchIn(scope)
        settingsRepo.settings.onEach { settings = it }.launchIn(scope)
        repo.homeState.onEach { home = it }.launchIn(scope)
        scope.launch { repo.markServiceConnected() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val s = settings ?: return
        val now = Instant.now()
        when (InterruptGate.decide(pkg, blocked, s, home, now)) {
            Decision.Allow -> Unit
            Decision.ShowChoices -> launchInterrupt(pkg, InterruptActivity.MODE_CHOICES)
            Decision.BlockDuringLesson -> {
                val t = System.currentTimeMillis()
                if (t - lastDerailAt > 10_000) {
                    lastDerailAt = t
                    scope.launch { repo.recordDerail() }
                }
                launchInterrupt(pkg, InterruptActivity.MODE_LESSON)
            }
            Decision.RestExpired -> scope.launch {
                repo.endRest()
                launchInterrupt(pkg, InterruptActivity.MODE_CHOICES)
            }
        }
    }

    private fun launchInterrupt(pkg: String, mode: String) {
        val t = System.currentTimeMillis()
        if (pkg == lastLaunchPackage && t - lastLaunchAt < 3_000) return
        lastLaunchPackage = pkg
        lastLaunchAt = t
        startActivity(
            Intent(this, InterruptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(InterruptActivity.EXTRA_PACKAGE, pkg)
                .putExtra(InterruptActivity.EXTRA_MODE, mode),
        )
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        scope.launch { repo.markServiceDisconnected() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
