package dev.kunoshoma.produce.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActionTemplateEntity::class,
        ActionLogEntity::class,
        RestLogEntity::class,
        DayStateEntity::class,
        ActiveLessonEntity::class,
        BlockedAppEntity::class,
        ServiceGapEntity::class,
        DailyReportEntity::class,
        WeeklyReportEntity::class,
        GoalEntity::class,
        GoalTargetEntity::class,
        ExamResultEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun actionTemplates(): ActionTemplateDao
    abstract fun actionLogs(): ActionLogDao
    abstract fun restLogs(): RestLogDao
    abstract fun dayStates(): DayStateDao
    abstract fun activeLesson(): ActiveLessonDao
    abstract fun blockedApps(): BlockedAppDao
    abstract fun serviceGaps(): ServiceGapDao
    abstract fun dailyReports(): DailyReportDao
    abstract fun weeklyReports(): WeeklyReportDao
    abstract fun goals(): GoalDao
    abstract fun examResults(): ExamResultDao

    companion object {
        /** v2: rest_logs.actionLogId（自己申告娯楽と元ログの紐付け）、day_states.choiceSeed（3 択の手札固定）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rest_logs ADD COLUMN actionLogId INTEGER")
                db.execSQL("ALTER TABLE day_states ADD COLUMN choiceSeed INTEGER")
                // v1 では終了されずに残った休むが起こり得た。v2 からは「未終了の行＝進行中」として扱うので、
                // 古い未終了行は許可時間ちょうどで終了済みにしておく（回復は付けない）。
                db.execSQL(
                    "UPDATE rest_logs SET endedAt = startedAt + allowedMin * 60000, actualMin = allowedMin WHERE endedAt IS NULL",
                )
                db.execSQL("UPDATE day_states SET activeRestLogId = NULL WHERE activeRestLogId IS NOT NULL")
            }
        }
    }
}
