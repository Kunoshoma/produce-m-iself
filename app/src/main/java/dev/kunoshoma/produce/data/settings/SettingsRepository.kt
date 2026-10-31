package dev.kunoshoma.produce.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.rules.QuietHours
import dev.kunoshoma.produce.core.rules.RankThresholds
import dev.kunoshoma.produce.core.rules.StaminaConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** アプリ設定（SPEC 3.2 / 3.6 / 3.7 / 6 設定画面）。 */
data class Settings(
    val stamina: StaminaConfig = StaminaConfig(),
    val rank: RankThresholds = RankThresholds(),
    val restMinuteOptions: List<Int> = listOf(30, 60),
    val dayBoundaryHour: Int = 5,
    val redrawsPerDay: Int = 2,
    val spChance: Double = 0.15,
    val axisLabels: Map<Axis, String> = Axis.entries.associateWith { it.defaultLabel },
    val onboardingDone: Boolean = false,
    /** 割り込み（娯楽アプリ検知）を使うか。アクセシビリティ権限の ON/OFF とは別のアプリ内スイッチ。 */
    val interruptEnabled: Boolean = true,
    val quietHours: QuietHours = QuietHours(),
    val bypassMinutes: Int = 5,
    val bypassesPerDay: Int = 3,
) {
    fun label(axis: Axis): String = axisLabels[axis] ?: axis.defaultLabel
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val staminaMax = intPreferencesKey("stamina_max")
        val recoveryRate = doublePreferencesKey("recovery_rate")
        val restPer15 = intPreferencesKey("rest_per_15")
        val restCap = intPreferencesKey("rest_cap")
        val lowThreshold = intPreferencesKey("low_threshold")
        val highThreshold = intPreferencesKey("high_threshold")
        val rankS = intPreferencesKey("rank_s")
        val rankA = intPreferencesKey("rank_a")
        val rankB = intPreferencesKey("rank_b")
        val rankC = intPreferencesKey("rank_c")
        val restOptions = stringPreferencesKey("rest_options")
        val dayBoundary = intPreferencesKey("day_boundary")
        val redraws = intPreferencesKey("redraws")
        val spChance = doublePreferencesKey("sp_chance")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val interruptEnabled = booleanPreferencesKey("interrupt_enabled")
        val quietEnabled = booleanPreferencesKey("quiet_enabled")
        val quietStart = intPreferencesKey("quiet_start")
        val quietEnd = intPreferencesKey("quiet_end")
        val bypassMinutes = intPreferencesKey("bypass_minutes")
        val bypassesPerDay = intPreferencesKey("bypasses_per_day")
        fun axisLabel(axis: Axis) = stringPreferencesKey("axis_label_${axis.name}")
    }

    val settings: Flow<Settings> = context.settingsStore.data.map { p ->
        val d = Settings()
        Settings(
            stamina = StaminaConfig(
                max = p[Keys.staminaMax] ?: d.stamina.max,
                recoveryRate = p[Keys.recoveryRate] ?: d.stamina.recoveryRate,
                restRecoveryPer15Min = p[Keys.restPer15] ?: d.stamina.restRecoveryPer15Min,
                restDailyCap = p[Keys.restCap] ?: d.stamina.restDailyCap,
                lowThreshold = p[Keys.lowThreshold] ?: d.stamina.lowThreshold,
                highThreshold = p[Keys.highThreshold] ?: d.stamina.highThreshold,
            ),
            rank = runCatching {
                RankThresholds(
                    s = p[Keys.rankS] ?: d.rank.s,
                    a = p[Keys.rankA] ?: d.rank.a,
                    b = p[Keys.rankB] ?: d.rank.b,
                    c = p[Keys.rankC] ?: d.rank.c,
                )
            }.getOrDefault(d.rank),
            restMinuteOptions = p[Keys.restOptions]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it > 0 }?.takeIf { it.isNotEmpty() } ?: d.restMinuteOptions,
            dayBoundaryHour = p[Keys.dayBoundary] ?: d.dayBoundaryHour,
            redrawsPerDay = p[Keys.redraws] ?: d.redrawsPerDay,
            spChance = p[Keys.spChance] ?: d.spChance,
            axisLabels = Axis.entries.associateWith { a -> p[Keys.axisLabel(a)]?.takeIf { it.isNotBlank() } ?: a.defaultLabel },
            onboardingDone = p[Keys.onboardingDone] ?: false,
            interruptEnabled = p[Keys.interruptEnabled] ?: d.interruptEnabled,
            quietHours = runCatching {
                QuietHours(
                    enabled = p[Keys.quietEnabled] ?: d.quietHours.enabled,
                    startHour = p[Keys.quietStart] ?: d.quietHours.startHour,
                    endHour = p[Keys.quietEnd] ?: d.quietHours.endHour,
                )
            }.getOrDefault(d.quietHours),
            bypassMinutes = p[Keys.bypassMinutes] ?: d.bypassMinutes,
            bypassesPerDay = p[Keys.bypassesPerDay] ?: d.bypassesPerDay,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(block: (Settings) -> Settings) {
        val next = block(current())
        context.settingsStore.edit { p ->
            p[Keys.staminaMax] = next.stamina.max
            p[Keys.recoveryRate] = next.stamina.recoveryRate
            p[Keys.restPer15] = next.stamina.restRecoveryPer15Min
            p[Keys.restCap] = next.stamina.restDailyCap
            p[Keys.lowThreshold] = next.stamina.lowThreshold
            p[Keys.highThreshold] = next.stamina.highThreshold
            p[Keys.rankS] = next.rank.s
            p[Keys.rankA] = next.rank.a
            p[Keys.rankB] = next.rank.b
            p[Keys.rankC] = next.rank.c
            p[Keys.restOptions] = next.restMinuteOptions.joinToString(",")
            p[Keys.dayBoundary] = next.dayBoundaryHour
            p[Keys.redraws] = next.redrawsPerDay
            p[Keys.spChance] = next.spChance
            p[Keys.onboardingDone] = next.onboardingDone
            p[Keys.interruptEnabled] = next.interruptEnabled
            p[Keys.quietEnabled] = next.quietHours.enabled
            p[Keys.quietStart] = next.quietHours.startHour
            p[Keys.quietEnd] = next.quietHours.endHour
            p[Keys.bypassMinutes] = next.bypassMinutes
            p[Keys.bypassesPerDay] = next.bypassesPerDay
            Axis.entries.forEach { a -> p[Keys.axisLabel(a)] = next.label(a) }
        }
    }
}
