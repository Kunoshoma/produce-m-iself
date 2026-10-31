package dev.kunoshoma.produce.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    ],
    version = 1,
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
}
