package dev.kunoshoma.produce.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

/** 設定ファイルが壊れていたら（電源断など）既定値で作り直す。読めなくて起動できないより、設定が戻るほうがまし。 */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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

    val settings: Flow<Settings> = context.settingsStore.data.map { p -> decode(p) }

    suspend fun current(): Settings = settings.first()

    /**
     * 読んで・変えて・書くを DataStore のトランザクション内で行う。
     * 画面から連続して呼ばれても（キー入力ごとに 1 回など）、先の変更が後の変更に上書きされない。
     * 戻り値は書き込んだあとの設定。
     */
    suspend fun update(block: (Settings) -> Settings): Settings {
        val result = context.settingsStore.edit { p -> write(p, block(decode(p))) }
        return decode(result)
    }

    /**
     * Preferences → Settings。保存値は復元（バックアップ）や古いバージョンで範囲外になり得るので、
     * ここで安全な範囲に丸める。範囲外の値がそのまま StaminaConfig / ProduceCalendar の require に届くと、
     * 設定を読むすべての画面が起動時に落ちる。
     */
    private fun decode(p: Preferences): Settings {
        val d = Settings()
        val stamina = runCatching {
            StaminaConfig(
                max = (p[Keys.staminaMax] ?: d.stamina.max).coerceAtLeast(1),
                recoveryRate = (p[Keys.recoveryRate] ?: d.stamina.recoveryRate).takeIf { it.isFinite() }?.coerceIn(0.1, 1.0)
                    ?: d.stamina.recoveryRate,
                restRecoveryPer15Min = (p[Keys.restPer15] ?: d.stamina.restRecoveryPer15Min).coerceAtLeast(0),
                restDailyCap = (p[Keys.restCap] ?: d.stamina.restDailyCap).coerceAtLeast(0),
                lowThreshold = (p[Keys.lowThreshold] ?: d.stamina.lowThreshold).coerceAtLeast(0),
                highThreshold = (p[Keys.highThreshold] ?: d.stamina.highThreshold).coerceAtLeast(0),
            )
        }.getOrDefault(d.stamina)
        val rank = runCatching {
            RankThresholds(
                s = p[Keys.rankS] ?: d.rank.s,
                a = p[Keys.rankA] ?: d.rank.a,
                b = p[Keys.rankB] ?: d.rank.b,
                c = p[Keys.rankC] ?: d.rank.c,
            )
        }.getOrDefault(d.rank)
        val quiet = runCatching {
            QuietHours(
                enabled = p[Keys.quietEnabled] ?: d.quietHours.enabled,
                startHour = p[Keys.quietStart] ?: d.quietHours.startHour,
                endHour = p[Keys.quietEnd] ?: d.quietHours.endHour,
            )
        }.getOrDefault(d.quietHours)
        return Settings(
            stamina = stamina,
            rank = rank,
            restMinuteOptions = p[Keys.restOptions]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it > 0 }?.takeIf { it.isNotEmpty() } ?: d.restMinuteOptions,
            dayBoundaryHour = (p[Keys.dayBoundary] ?: d.dayBoundaryHour).coerceIn(0, 23),
            redrawsPerDay = (p[Keys.redraws] ?: d.redrawsPerDay).coerceAtLeast(0),
            spChance = (p[Keys.spChance] ?: d.spChance).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: d.spChance,
            axisLabels = Axis.entries.associateWith { a -> p[Keys.axisLabel(a)]?.takeIf { it.isNotBlank() } ?: a.defaultLabel },
            onboardingDone = p[Keys.onboardingDone] ?: false,
            interruptEnabled = p[Keys.interruptEnabled] ?: d.interruptEnabled,
            quietHours = quiet,
            bypassMinutes = (p[Keys.bypassMinutes] ?: d.bypassMinutes).coerceAtLeast(1),
            bypassesPerDay = (p[Keys.bypassesPerDay] ?: d.bypassesPerDay).coerceAtLeast(0),
        )
    }

    private fun write(p: MutablePreferences, next: Settings) {
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
