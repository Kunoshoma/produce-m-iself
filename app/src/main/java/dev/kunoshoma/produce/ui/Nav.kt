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

@Composable
fun ProduceNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onProduce = { nav.navigate(Routes.PRODUCE) },
                onLesson = { nav.navigate(Routes.LESSON) },
                onManual = { nav.navigate(Routes.MANUAL) },
                onActions = { nav.navigate(Routes.ACTIONS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onReports = { nav.navigate(Routes.REPORTS) },
                onGoal = { nav.navigate(Routes.GOAL) },
            )
        }
        composable(Routes.PRODUCE) {
            ProduceScreen(
                onBack = { nav.popBackStack() },
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
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate(Routes.actionEdit(id)) },
            )
        }
        composable(
            Routes.ACTION_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            ActionEditScreen(id = entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable(Routes.MANUAL) {
            ManualLogScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onBlockedApps = { nav.navigate(Routes.BLOCKED_APPS) })
        }
        composable(Routes.BLOCKED_APPS) {
            BlockedAppsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.GOAL) {
            GoalScreen(onBack = { nav.popBackStack() }, onCreate = { nav.navigate(Routes.GOAL_CREATE) })
        }
        composable(Routes.GOAL_CREATE) {
            GoalCreateScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.REPORTS) {
            ReportsScreen(
                onBack = { nav.popBackStack() },
                onDaily = { nav.navigate(Routes.dailyReport(it)) },
                onWeekly = { nav.navigate(Routes.weeklyReport(it)) },
            )
        }
        composable(Routes.DAILY_REPORT, arguments = listOf(navArgument("date") { type = NavType.StringType })) { entry ->
            DailyReportScreen(date = entry.arguments?.getString("date") ?: "", onBack = { nav.popBackStack() })
        }
        composable(Routes.WEEKLY_REPORT, arguments = listOf(navArgument("weekStart") { type = NavType.StringType })) { entry ->
            WeeklyReportScreen(weekStart = entry.arguments?.getString("weekStart") ?: "", onBack = { nav.popBackStack() })
        }
    }
}
