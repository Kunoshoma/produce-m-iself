package dev.kunoshoma.produce.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.kunoshoma.produce.ui.actions.ActionEditScreen
import dev.kunoshoma.produce.ui.actions.ActionsScreen
import dev.kunoshoma.produce.ui.blocked.BlockedAppsScreen
import dev.kunoshoma.produce.ui.goal.GoalCreateScreen
import dev.kunoshoma.produce.ui.goal.GoalScreen
import dev.kunoshoma.produce.ui.home.HomeScreen
import dev.kunoshoma.produce.ui.lesson.LessonScreen
import dev.kunoshoma.produce.ui.manual.ManualLogScreen
import dev.kunoshoma.produce.ui.produce.ProduceScreen
import dev.kunoshoma.produce.ui.reports.DailyReportScreen
import dev.kunoshoma.produce.ui.reports.ReportsScreen
import dev.kunoshoma.produce.ui.reports.WeeklyReportScreen
import dev.kunoshoma.produce.ui.result.ResultScreen
import dev.kunoshoma.produce.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val PRODUCE = "produce"
    const val LESSON = "lesson"
    const val RESULT = "result"
    const val ACTIONS = "actions"
    const val ACTION_EDIT = "action_edit/{id}"
    const val MANUAL = "manual"
    const val SETTINGS = "settings"
    const val BLOCKED_APPS = "blocked_apps"
    const val REPORTS = "reports"
    const val GOAL = "goal"
    const val GOAL_CREATE = "goal/create"
    const val DAILY_REPORT = "report/daily/{date}"
    const val WEEKLY_REPORT = "report/weekly/{weekStart}"

    fun dailyReport(date: String) = "report/daily/$date"
    fun weeklyReport(weekStart: String) = "report/weekly/$weekStart"

    fun actionEdit(id: Long) = "action_edit/$id"
}

/**
 * [route] の画面が最前面にあるときだけ 1 つ戻る。
 * 戻る矢印を素早く 2 回タップすると、1 回目で pop された画面がフェードアウト中に 2 回目のタップを受け取り、
 * その下の HOME まで pop されて真っ黒な NavHost だけが残る。pop 元の画面が今の destination であること
 * （＝まだ pop されていない）と、下に戻り先があることを確認してから pop する。
 */
private fun NavHostController.popFrom(route: String) {
    if (currentDestination?.route == route && previousBackStackEntry != null) popBackStack()
}

/** HOME からの前進。ボタンの 2 度押しで同じ画面が 2 枚積まれないようにする。 */
private fun NavHostController.go(route: String) {
    navigate(route) { launchSingleTop = true }
}

@Composable
fun ProduceNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onProduce = { nav.go(Routes.PRODUCE) },
                onLesson = { nav.go(Routes.LESSON) },
                onManual = { nav.go(Routes.MANUAL) },
                onActions = { nav.go(Routes.ACTIONS) },
                onSettings = { nav.go(Routes.SETTINGS) },
                onReports = { nav.go(Routes.REPORTS) },
                onGoal = { nav.go(Routes.GOAL) },
            )
        }
        composable(Routes.PRODUCE) {
            ProduceScreen(
                onBack = { nav.popFrom(Routes.PRODUCE) },
                onLessonStarted = { nav.navigate(Routes.LESSON) { popUpTo(Routes.HOME) } },
                onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.LESSON) {
            LessonScreen(
                onFinish = { nav.navigate(Routes.RESULT) { popUpTo(Routes.HOME) } },
                onBack = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.RESULT) {
            ResultScreen(onHome = { nav.popBackStack(Routes.HOME, inclusive = false) })
        }
        composable(Routes.ACTIONS) {
            ActionsScreen(
                onBack = { nav.popFrom(Routes.ACTIONS) },
                onEdit = { id -> nav.navigate(Routes.actionEdit(id)) },
            )
        }
        composable(
            Routes.ACTION_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            ActionEditScreen(id = entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popFrom(Routes.ACTION_EDIT) })
        }
        composable(Routes.MANUAL) {
            ManualLogScreen(onBack = { nav.popFrom(Routes.MANUAL) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popFrom(Routes.SETTINGS) }, onBlockedApps = { nav.go(Routes.BLOCKED_APPS) })
        }
        composable(Routes.BLOCKED_APPS) {
            BlockedAppsScreen(onBack = { nav.popFrom(Routes.BLOCKED_APPS) })
        }
        composable(Routes.GOAL) {
            GoalScreen(onBack = { nav.popFrom(Routes.GOAL) }, onCreate = { nav.go(Routes.GOAL_CREATE) })
        }
        composable(Routes.GOAL_CREATE) {
            GoalCreateScreen(onBack = { nav.popFrom(Routes.GOAL_CREATE) })
        }
        composable(Routes.REPORTS) {
            ReportsScreen(
                onBack = { nav.popFrom(Routes.REPORTS) },
                onDaily = { nav.navigate(Routes.dailyReport(it)) },
                onWeekly = { nav.navigate(Routes.weeklyReport(it)) },
            )
        }
        composable(Routes.DAILY_REPORT, arguments = listOf(navArgument("date") { type = NavType.StringType })) { entry ->
            DailyReportScreen(date = entry.arguments?.getString("date") ?: "", onBack = { nav.popFrom(Routes.DAILY_REPORT) })
        }
        composable(Routes.WEEKLY_REPORT, arguments = listOf(navArgument("weekStart") { type = NavType.StringType })) { entry ->
            WeeklyReportScreen(weekStart = entry.arguments?.getString("weekStart") ?: "", onBack = { nav.popFrom(Routes.WEEKLY_REPORT) })
        }
    }
}
