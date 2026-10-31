package dev.kunoshoma.produce.interrupt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.kunoshoma.produce.ui.MainActivity
import dev.kunoshoma.produce.ui.Routes
import dev.kunoshoma.produce.ui.theme.ProduceTheme

/** 娯楽アプリの上に出る全画面の 3 択（SPEC 4.1）。 */
@AndroidEntryPoint
class InterruptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CHOICES
        setContent {
            ProduceTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    InterruptScreen(
                        packageName = pkg,
                        mode = mode,
                        onLessonStarted = { openApp(Routes.LESSON) },
                        onRestStarted = { finish() },        // 娯楽アプリに戻る
                        onGoHome = { goHome() },
                        onBypass = { finish() },
                    )
                }
            }
        }
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
    }
}
