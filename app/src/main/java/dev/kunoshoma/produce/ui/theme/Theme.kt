package dev.kunoshoma.produce.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.core.rules.Judgement

val Night = Color(0xFF121218)
val NightSurface = Color(0xFF1B1B26)
val NightSurfaceHigh = Color(0xFF262635)
val Accent = Color(0xFFB388FF)
val StaminaGreen = Color(0xFF3DDC97)
val StaminaRed = Color(0xFFE5484D)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1B0B3B),
    secondary = Color(0xFF80CBC4),
    background = Night,
    surface = NightSurface,
    surfaceVariant = NightSurfaceHigh,
    onBackground = Color(0xFFEDEDF2),
    onSurface = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFFB8B8C8),
    error = StaminaRed,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6A3FD1),
    secondary = Color(0xFF00897B),
)

@Composable
/** ダーク基調（SPEC 7 章）。プラットフォームテーマ・ウィジェットもダーク固定なので Compose 側も合わせる。 */
fun ProduceTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, content = content)
}

fun Axis.color(): Color = Color(defaultColorHex)

fun Condition.color(): Color = when (this) {
    Condition.NORMAL -> Color(0xFF9E9EB0)
    Condition.GOOD -> Color(0xFFFFB74D)
    Condition.EXCELLENT -> Color(0xFFFF7043)
}

fun Judgement.color(): Color = when (this) {
    Judgement.ACHIEVED -> StaminaGreen
    Judgement.ON_TRACK -> StaminaGreen
    Judgement.WARN -> Color(0xFFFFB74D)
    Judgement.DANGER -> StaminaRed
}

fun staminaColor(ratio: Float): Color = when {
    ratio <= 0.3f -> StaminaRed
    ratio <= 0.6f -> Color(0xFFFFB74D)
    else -> StaminaGreen
}
