package dev.kunoshoma.produce.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.kunoshoma.produce.ui.theme.ProduceTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 通知・ウィジェット・割り込み画面から渡された遷移先。消費したら null に戻す。 */
    private var pendingRoute by mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // アプリはダーク固定なので、端末がライトモードでもステータスバー / ナビバーのアイコンは白にする
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // 回転やダークモード切替での再生成では Intent を読み直さない。
        // 読み直すと「レッスン終了」通知の route=result が回転ごとに再送され、
        // 記録済みなのに結果画面へ飛ばされる。NavController 側は自前で back stack を復元する。
        if (savedInstanceState == null) {
            pendingRoute = consumeRoute(intent)
            requestNotificationPermissionIfNeeded()
        }
        setContent {
            ProduceTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    ProduceNavHost(nav)
                    val route = pendingRoute
                    LaunchedEffect(route) {
                        if (route != null) {
                            // タイマー / 結果画面はホーム直上に置く（Nav.kt と同じ）。
                            // 割り込み画面からレッスンを始めたとき、下に古い 3 択画面が残らないようにする。
                            // すでにその画面が最前面なら pop せず、入力途中の状態を守る。
                            val popToHome = (route == Routes.LESSON || route == Routes.RESULT) &&
                                nav.currentDestination?.route != route
                            nav.navigate(route) {
                                launchSingleTop = true
                                if (popToHome) popUpTo(Routes.HOME)
                            }
                            pendingRoute = null
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // getIntent() を最新にしておく（singleTask なので 2 回目以降はここに来る）
        setIntent(intent)
        consumeRoute(intent)?.let { pendingRoute = it }
    }

    /** Intent から遷移先を 1 回だけ取り出す。取り出したら extra を消し、Activity 再生成時に再送されないようにする。 */
    private fun consumeRoute(intent: Intent?): String? {
        if (intent == null) return null
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return null
        intent.removeExtra(EXTRA_ROUTE)
        return route
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_RESULT = Routes.RESULT
        const val ROUTE_PRODUCE = Routes.PRODUCE
        const val ROUTE_REPORTS = Routes.REPORTS
        const val ROUTE_SETTINGS = Routes.SETTINGS
        const val ROUTE_GOAL = Routes.GOAL
    }
}
