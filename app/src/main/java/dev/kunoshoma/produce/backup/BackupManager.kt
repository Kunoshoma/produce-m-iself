package dev.kunoshoma.produce.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kunoshoma.produce.core.rules.QuietHours
import dev.kunoshoma.produce.core.rules.RankThresholds
import dev.kunoshoma.produce.core.rules.StaminaConfig
import dev.kunoshoma.produce.core.time.ProduceCalendar
import dev.kunoshoma.produce.data.db.AppDatabase
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JSON フルバックアップ / 復元（SPEC 13.4）。
 * Room の各テーブルをそのまま配列で書き出す。復元は全テーブルを置き換える。
 * 自動バックアップは共有ストレージの Download/produce に直近 4 世代（アンインストールしても残る）。通信は発生しない。
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val clock: Clock,
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

    /**
     * 自動バックアップ。プロデュース日ごとに 1 回まで（周期ジョブが 1 日に何度も走っても世代が回り過ぎない）。
     * 保存したら true。
     */
    suspend fun autoBackupIfDue(): Boolean {
        val hour = settingsRepo.current().dayBoundaryHour
        val today = ProduceCalendar(clock.zone, hour).produceDate(clock.instant()).toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_AUTO_BACKUP_DATE, null) == today) return false
        autoBackup()
        prefs.edit().putString(KEY_LAST_AUTO_BACKUP_DATE, today).apply()
        return true
    }

    /**
     * 共有ストレージ（Download/produce）へ自動保存し、直近 4 世代だけ残す。
     * アプリ専用外部ストレージはアンインストールで消えるので、再インストール時に拾える場所に置く（SPEC 13.4）。
     * MediaStore 経由なので権限は不要。消せるのはこのインストールが作ったファイルだけ。
     */
    suspend fun autoBackup() = withContext(Dispatchers.IO) {
        val text = buildJson()
        val name = "$AUTO_PREFIX${DateTimeFormatter.ISO_INSTANT.format(clock.instant()).replace(':', '-')}.json"
        writeToDownloads(name, text)
        pruneDownloads(keep = KEEP_GENERATIONS)
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
            exportedAt = clock.instant().toString(),
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

    /**
     * テーブルを生 SQL で読んで文字列のマップに落とす。スキーマ変更に強く、型は復元時に SQLite が解釈する。
     * REAL 列は getString だと 6 桁に丸められるので、型ごとに読んで往復で値が変わらないようにする。
     */
    private fun readTable(table: String): List<Map<String, String?>> {
        val rows = mutableListOf<Map<String, String?>>()
        db.openHelper.readableDatabase.query("SELECT * FROM $table").use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                rows += names.indices.associate { i ->
                    names[i] to when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
                        else -> c.getString(i)
                    }
                }
            }
        }
        return rows
    }

    private suspend fun restore(payload: Payload) {
        // 先に設定を組み立てて検証する（範囲外なら DB に触る前に失敗させる。DB だけ入れ替わって設定が残る半端な復元を防ぐ）
        val next = buildSettings(settingsRepo.current(), payload.settings)
        // Room のトランザクションで包む：終了時に InvalidationTracker が更新され、購読中の Flow が復元後の行を読み直す
        db.withTransaction {
            val sqlite = db.openHelper.writableDatabase
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
        }
        settingsRepo.update { next }
    }

    /** バックアップ内の設定を今の設定に重ねる。値の検証はデータクラスの init（StaminaConfig など）に任せ、失敗は例外で返す。 */
    private fun buildSettings(current: Settings, ss: SettingsSnapshot): Settings {
        require(ss.dayBoundaryHour in 0..23) { "日付境界が範囲外（${ss.dayBoundaryHour}）" }
        require(ss.staminaMax > 0) { "最大体力が範囲外（${ss.staminaMax}）" }
        require(ss.recoveryRate.isFinite() && ss.recoveryRate in 0.0..1.0) { "回復率が範囲外（${ss.recoveryRate}）" }
        val stamina = StaminaConfig(
            max = ss.staminaMax, recoveryRate = ss.recoveryRate, restRecoveryPer15Min = ss.restPer15.coerceAtLeast(0),
            restDailyCap = ss.restCap.coerceAtLeast(0), lowThreshold = ss.lowThreshold.coerceAtLeast(0),
            highThreshold = ss.highThreshold.coerceAtLeast(0), lowMultiplier = current.stamina.lowMultiplier,
        )
        val rank = runCatching { RankThresholds(s = ss.rankS, a = ss.rankA, b = ss.rankB, c = ss.rankC) }.getOrDefault(current.rank)
        val quiet = runCatching { QuietHours(enabled = ss.quietEnabled, startHour = ss.quietStart, endHour = ss.quietEnd) }.getOrDefault(current.quietHours)
        return current.copy(
            stamina = stamina,
            rank = rank,
            restMinuteOptions = ss.restOptions.filter { it > 0 }.ifEmpty { current.restMinuteOptions },
            dayBoundaryHour = ss.dayBoundaryHour,
            redrawsPerDay = ss.redrawsPerDay.coerceAtLeast(0),
            spChance = if (ss.spChance.isFinite()) ss.spChance.coerceIn(0.0, 1.0) else current.spChance,
            axisLabels = current.axisLabels.mapValues { (axis, label) -> ss.axisLabels[axis.name]?.takeIf { it.isNotBlank() } ?: label },
            interruptEnabled = ss.interruptEnabled,
            quietHours = quiet,
            bypassMinutes = ss.bypassMinutes.coerceAtLeast(1),
            bypassesPerDay = ss.bypassesPerDay.coerceAtLeast(0),
        )
    }

    /** MediaStore の Downloads に 1 ファイル書く。書けなかったら作った行を消して例外を投げる。 */
    private fun writeToDownloads(name: String, text: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, autoBackupDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("自動バックアップの保存先を作れなかった")
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("自動バックアップを書けなかった")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** このアプリが作った自動バックアップのうち、新しい [keep] 件以外を消す（名前に時刻が入っているので名前順）。 */
    private fun pruneDownloads(keep: Int) {
        val resolver = context.contentResolver
        val found = mutableListOf<Pair<Long, String>>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$autoBackupDir%", "$AUTO_PREFIX%.json"),
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) found += c.getLong(idCol) to c.getString(nameCol)
        }
        found.sortedByDescending { it.second }.drop(keep).forEach { (id, _) ->
            runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val KEEP_GENERATIONS = 4
        private const val PREFS = "backup"
        private const val KEY_LAST_AUTO_BACKUP_DATE = "last_auto_backup_date"
        private const val AUTO_PREFIX = "produce-"
        /** 自動バックアップの置き場所（共有ストレージの Download 配下）。設定画面の説明文と合わせる。 */
        const val AUTO_BACKUP_FOLDER = "produce"
        private val autoBackupDir: String = Environment.DIRECTORY_DOWNLOADS + "/" + AUTO_BACKUP_FOLDER
        /** 外部キーはないが、参照の向きに合わせた順。 */
        val TABLES = listOf(
            "action_templates", "day_states", "action_logs", "rest_logs", "active_lesson",
            "blocked_apps", "service_gaps", "daily_reports", "weekly_reports",
            "goals", "goal_targets", "exam_results",
        )
        fun suggestedFileName(): String = "produce-backup-${java.time.LocalDate.now()}.json"
    }
}
