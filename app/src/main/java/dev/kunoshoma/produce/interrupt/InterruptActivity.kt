package dev.kunoshoma.produce.interrupt

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.kunoshoma.produce.ui.MainActivity
import dev.kunoshoma.produce.ui.Routes
import dev.kunoshoma.produce.ui.theme.ProduceTheme

/** 娯楽アプリの上に出る全画面の 3 択（SPEC 4.1）。 */
@AndroidEntryPoint
class InterruptActivity : ComponentActivity() {

    // singleInstance なので 2 回目以降の起動は onNewIntent に来る。Compose の状態として持ち、差し替えで再描画する
    private var pkg by mutableStateOf<String?>(null)
    private var mode by mutableStateOf(MODE_CHOICES)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // アプリはダーク固定なので、端末がライトモードでもステータスバー / ナビバーのアイコンは白にする
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        readExtras(intent)
        setContent {
            ProduceTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    InterruptScreen(
                        packageName = pkg,
                        mode = mode,
                        onLessonStarted = { openApp(Routes.LESSON) },
                        onRestStarted = { noteGrant(); finish() },   // 娯楽アプリに戻る
                        onGoHome = { goHome() },
                        onBypass = { noteGrant(); finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
    }

    override fun onPause() {
        isShowing = false
        super.onPause()
    }

    private fun readExtras(intent: Intent?) {
        pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_CHOICES
    }

    /** 休む・バイパスが成立した時刻。サービス側が、直後に戻ってくる娯楽アプリのイベントを通すのに使う。 */
    private fun noteGrant() {
        lastGrantAt = SystemClock.elapsedRealtime()
    }

    private fun openApp(route: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, route),
        )
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_MODE = "mode"
        const val MODE_CHOICES = "choices"
        const val MODE_LESSON = "lesson"

        /** 割り込み画面が前面にあるか（onResume〜onPause）。サービスの再起動抑制に使う。 */
        @Volatile var isShowing: Boolean = false

        /** 直前に休む・バイパスが成立した時刻（SystemClock.elapsedRealtime）。0 なら未成立。 */
        @Volatile var lastGrantAt: Long = 0L
    }
}
