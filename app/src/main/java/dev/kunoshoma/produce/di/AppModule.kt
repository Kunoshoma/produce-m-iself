package dev.kunoshoma.produce.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kunoshoma.produce.data.db.AppDatabase
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "produce.db")
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun actionTemplates(db: AppDatabase) = db.actionTemplates()
    @Provides fun actionLogs(db: AppDatabase) = db.actionLogs()
    @Provides fun restLogs(db: AppDatabase) = db.restLogs()
    @Provides fun dayStates(db: AppDatabase) = db.dayStates()
    @Provides fun activeLesson(db: AppDatabase) = db.activeLesson()
    @Provides fun blockedApps(db: AppDatabase) = db.blockedApps()
    @Provides fun serviceGaps(db: AppDatabase) = db.serviceGaps()
    @Provides fun dailyReports(db: AppDatabase) = db.dailyReports()
    @Provides fun weeklyReports(db: AppDatabase) = db.weeklyReports()
    @Provides fun goals(db: AppDatabase) = db.goals()
    @Provides fun examResults(db: AppDatabase) = db.examResults()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()
}
