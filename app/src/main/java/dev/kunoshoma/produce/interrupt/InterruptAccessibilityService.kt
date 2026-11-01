package dev.kunoshoma.produce.interrupt

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
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
 * 監視するパッケージもブロック対象だけに絞る（[applyPackageFilter]）。
 */
@AndroidEntryPoint
class InterruptAccessibilityService : AccessibilityService() {

    @Inject lateinit var repo: ProduceRepository
    @Inject lateinit var settingsRepo: SettingsRepository

    /** Flow の購読用。onDestroy で止める。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var settings: Settings? = null
    @Volatile private var home: HomeState? = null

    private var lastLaunchPackage: String? = null
    private var lastLaunchMode: String? = null
    // 時刻は SystemClock.elapsedRealtime()（単調増加）。端末の時計を戻されても抑制が壊れない
    private var lastLaunchAt: Long = 0
    private var lastDerailAt: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo.observeBlockedPackages().onEach { list ->
            val set = list.toSet()
            blocked = set
            applyPackageFilter(set)
        }.launchIn(scope)
        settingsRepo.settings.onEach { settings = it }.launchIn(scope)
        repo.homeState.onEach { home = it }.launchIn(scope)
        ServiceWatchdogWorker.noteServiceConnected(this)
        writeScope.launch { repo.markServiceConnected() }
    }

    /**
     * 監視対象をブロック中のアプリだけに絞る（SPEC 4.1「packageNames を対象アプリに限定」）。
     * 空配列は「全アプリ」の意味になるので、何もブロックしていないときは自分自身だけにして実質イベントなしにする。
     */
    private fun applyPackageFilter(set: Set<String>) {
        val info = serviceInfo ?: return
        info.packageNames = if (set.isEmpty()) arrayOf(packageName) else set.toTypedArray()
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val s = settings ?: return
        // 状態がまだ読めていないうちは判断しない（fail open）。起動直後の誤割り込みを防ぐ
        val h = home ?: return
        // 割り込み画面で休む・バイパスが成立した直後は、homeState の更新が追い付く前のイベントを通す
        if (SystemClock.elapsedRealtime() - InterruptActivity.lastGrantAt < GRANT_GRACE_MILLIS) return
        val now = Instant.now()
        when (InterruptGate.decide(pkg, blocked, s, h, now)) {
            Decision.Allow -> Unit
            Decision.ShowChoices -> launchInterrupt(pkg, InterruptActivity.MODE_CHOICES)
            Decision.BlockDuringLesson -> {
                val t = SystemClock.elapsedRealtime()
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
        val t = SystemClock.elapsedRealtime()
        val same = pkg == lastLaunchPackage && mode == lastLaunchMode
        // 対象アプリ起動直後の連続イベントは 1 回にまとめる。それ以降は割り込み画面が前面にある間だけ抑制し、
        // 戻る・タスク終了などで決定なしに消えたら即出し直す（singleInstance なので重複起動は onNewIntent になる）
        if (same && (t - lastLaunchAt < LAUNCH_BURST_MILLIS || InterruptActivity.isShowing)) return
        lastLaunchPackage = pkg
        lastLaunchMode = mode
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
        // onUnbind の直後に onDestroy で scope が止まるので、プロセス寿命のスコープで書き込みを完了させる
        writeScope.launch { repo.markServiceDisconnected() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 対象アプリ起動時の WINDOW_STATE_CHANGED の連発をまとめる時間。 */
        private const val LAUNCH_BURST_MILLIS = 1_000L

        /** 休む・バイパス成立後、homeState の反映を待つ猶予。 */
        private const val GRANT_GRACE_MILLIS = 2_000L

        /** サービスのライフサイクルに縛られない一回限りの DB 書き込み用（service_gaps の開閉）。 */
        // 開く・閉じるの読み書きが追い越さないよう 1 本ずつ流す
        private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    }
}
