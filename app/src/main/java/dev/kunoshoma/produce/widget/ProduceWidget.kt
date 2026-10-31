package dev.kunoshoma.produce.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.domain.HomeState
import dev.kunoshoma.produce.domain.ProduceRepository
import dev.kunoshoma.produce.ui.MainActivity
import dev.kunoshoma.produce.ui.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** ホーム画面ウィジェット（SPEC 13.6）。体力・今日の 3 軸・連続日数・目標の残日数。タップで 3 択。 */
class ProduceWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun repo(): ProduceRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors.fromApplication(context, Deps::class.java).repo()
        val state = withTimeoutOrNull(5_000) { repo.homeState.first() }
        provideContent { GlanceTheme { Content(state) } }
    }

    @Composable
    private fun Content(s: HomeState?) {
        val bg = ColorProvider(Color(0xFF1B1B26))
        val fg = ColorProvider(Color(0xFFEDEDF2))
        val dim = ColorProvider(Color(0xFFB8B8C8))
        Column(
            modifier = GlanceModifier.fillMaxSize().background(bg).cornerRadius(16.dp).padding(12.dp)
                .clickable(actionStartActivity<MainActivity>(actionParametersOf(ROUTE_KEY to Routes.PRODUCE))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s == null) {
                Text("プロデュース", style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Text("タップして開く", style = TextStyle(color = dim, fontSize = 12.sp))
                return@Column
            }
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("体力 ${s.day.stamina}/${s.settings.stamina.max}", style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.defaultWeight())
                if (s.streak.displayStreak > 0) Text("${s.streak.displayStreak} 日連続", style = TextStyle(color = dim, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = (s.day.stamina.toFloat() / s.settings.stamina.max).coerceIn(0f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(8.dp),
                color = ColorProvider(if (s.day.stamina <= 30) Color(0xFFE5484D) else Color(0xFF3DDC97)),
                backgroundColor = ColorProvider(Color(0xFF262635)),
            )
            Spacer(GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Axis.entries.forEach { a ->
                    Text(
                        "${s.settings.label(a)} +${s.todayGains[a] ?: 0}",
                        style = TextStyle(color = ColorProvider(Color(a.defaultColorHex)), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    )
                    Spacer(GlanceModifier.width(10.dp))
                }
            }
            s.goal?.let { g ->
                Spacer(GlanceModifier.height(6.dp))
                Text("${g.name}：あと ${g.daysLeft} 日 ・ ${(g.progressRatio * 100).toInt()}%", style = TextStyle(color = dim, fontSize = 11.sp))
            }
        }
    }

    companion object {
        val ROUTE_KEY = ActionParameters.Key<String>(MainActivity.EXTRA_ROUTE)
    }
}

class ProduceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProduceWidget()
}

/** 状態が変わったときにウィジェットを更新する。失敗しても本体には影響させない。 */
@Singleton
class WidgetUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun update() {
        runCatching { ProduceWidget().updateAll(context) }
    }
}
