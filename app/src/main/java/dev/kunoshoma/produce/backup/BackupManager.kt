package dev.kunoshoma.produce.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kunoshoma.produce.data.db.AppDatabase
import dev.kunoshoma.produce.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JSON フルバックアップ / 復元（SPEC 13.4）。
 * Room の各テーブルをそのまま配列で書き出す。復元は全テーブルを置き換える。
 * 自動バックアップはアプリ専用外部ストレージに直近 4 世代。通信は発生しない。
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Payload(
        val schemaVersion: Int = SCHEMA_VERSION,
        val exportedAt: String,
        val settings: SettingsSnapshot,
        val tables: Map<String, List<Map<String, String?>>>,
    )

    @Serializable
    data class SettingsSnapshot(
        val staminaMax: Int, val recoveryRate: Double, val restPer15: Int, val restCap: Int,
        val lowThreshold: Int, val highThreshold: Int,
        val rankS: Int, val rankA: Int, val rankB: Int, val rankC: Int,
        val restOptions: List<Int>, val dayBoundaryHour: Int, val redrawsPerDay: Int, val spChance: Double,
        val axisLabels: Map<String, String>, val interruptEnabled: Boolean,
        val quietEnabled: Boolean, val quietStart: Int, val quietEnd: Int,
        val bypassMinutes: Int, val bypassesPerDay: Int,
    )

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = buildJson().toByteArray()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("書き込み先を開けなかった")
    }

    /** アプリ専用外部ストレージへ自動保存。直近 4 世代を残す。 */
    suspend fun autoBackup() = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val name = "produce-${DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-')}.json"
        File(dir, name).writeText(buildJson())
        dir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.name }?.drop(4)?.forEach { it.delete() }
    }

    suspend fun importFrom(uri: Uri) = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("読み込めなかった")
        val payload = json.decodeFromString(Payload.serializer(), text)
        require(payload.schemaVersion <= SCHEMA_VERSION) { "新しい形式のバックアップ（v${payload.schemaVersion}）。アプリを更新して" }
        restore(payload)
    }

    private suspend fun buildJson(): String {
        val s = settingsRepo.current()
        val tables = TABLES.associateWith { table -> readTable(table) }
        val payload = Payload(
            exportedAt = Instant.now().toString(),
            settings = SettingsSnapshot(
                staminaMax = s.stamina.max, recoveryRate = s.stamina.recoveryRate, restPer15 = s.stamina.restRecoveryPer15Min,
                restCap = s.stamina.restDailyCap, lowThreshold = s.stamina.lowThreshold, highThreshold = s.stamina.highThreshold,
                rankS = s.rank.s, rankA = s.rank.a, rankB = s.rank.b, rankC = s.rank.c,
                restOptions = s.restMinuteOptions, dayBoundaryHour = s.dayBoundaryHour, redrawsPerDay = s.redrawsPerDay, spChance = s.spChance,
                axisLabels = s.axisLabels.mapKeys { it.key.name }, interruptEnabled = s.interruptEnabled,
                quietEnabled = s.quietHours.enabled, quietStart = s.quietHours.startHour, quietEnd = s.quietHours.endHour,
                bypassMinutes = s.bypassMinutes, bypassesPerDay = s.bypassesPerDay,
            ),
            tables = tables,
        )
        return json.encodeToString(Payload.serializer(), payload)
    }

    /** テーブルを生 SQL で読んで文字列のマップに落とす。スキーマ変更に強く、型は復元時に SQLite が解釈する。 */
    private fun readTable(table: String): List<Map<String, String?>> {
        val rows = mutableListOf<Map<String, String?>>()
        db.openHelper.readableDatabase.query("SELECT * FROM $table").use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                rows += names.indices.associate { i -> names[i] to if (c.isNull(i)) null else c.getString(i) }
            }
        }
        return rows
    }

    private suspend fun restore(payload: Payload) {
        val sqlite = db.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            for (table in TABLES.reversed()) sqlite.execSQL("DELETE FROM $table")
            for (table in TABLES) {
                val rows = payload.tables[table] ?: continue
                for (row in rows) {
                    if (row.isEmpty()) continue
                    val cols = row.keys.toList()
                    val sql = "INSERT INTO $table (${cols.joinToString(",")}) VALUES (${cols.joinToString(",") { "?" }})"
                    sqlite.execSQL(sql, cols.map { row[it] }.toTypedArray())
                }
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
        val ss = payload.settings
        settingsRepo.update { s ->
            s.copy(
                stamina = s.stamina.copy(
                    max = ss.staminaMax, recoveryRate = ss.recoveryRate, restRecoveryPer15Min = ss.restPer15,
                    restDailyCap = ss.restCap, lowThreshold = ss.lowThreshold, highThreshold = ss.highThreshold,
                ),
                rank = runCatching { s.rank.copy(s = ss.rankS, a = ss.rankA, b = ss.rankB, c = ss.rankC) }.getOrDefault(s.rank),
                restMinuteOptions = ss.restOptions.ifEmpty { s.restMinuteOptions },
                dayBoundaryHour = ss.dayBoundaryHour, redrawsPerDay = ss.redrawsPerDay, spChance = ss.spChance,
                axisLabels = s.axisLabels.mapValues { (axis, label) -> ss.axisLabels[axis.name] ?: label },
                interruptEnabled = ss.interruptEnabled,
                quietHours = runCatching { s.quietHours.copy(enabled = ss.quietEnabled, startHour = ss.quietStart, endHour = ss.quietEnd) }.getOrDefault(s.quietHours),
                bypassMinutes = ss.bypassMinutes, bypassesPerDay = ss.bypassesPerDay,
            )
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        /** 外部キーはないが、参照の向きに合わせた順。 */
        val TABLES = listOf(
            "action_templates", "day_states", "action_logs", "rest_logs", "active_lesson",
            "blocked_apps", "service_gaps", "daily_reports", "weekly_reports",
        )
        fun suggestedFileName(): String = "produce-backup-${java.time.LocalDate.now()}.json"
    }
}
