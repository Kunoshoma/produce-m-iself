package dev.kunoshoma.produce.data.db

import androidx.room.TypeConverter
import dev.kunoshoma.produce.core.model.DayPart
import dev.kunoshoma.produce.core.model.FailReason
import java.time.Instant
import java.time.LocalDate

class Converters {
    @TypeConverter fun instantToLong(v: Instant?): Long? = v?.toEpochMilli()
    @TypeConverter fun longToInstant(v: Long?): Instant? = v?.let(Instant::ofEpochMilli)

    @TypeConverter fun dateToString(v: LocalDate?): String? = v?.toString()
    @TypeConverter fun stringToDate(v: String?): LocalDate? = v?.let(LocalDate::parse)

    @TypeConverter fun dayPartsToString(v: Set<DayPart>): String = v.joinToString(",") { it.name }
    @TypeConverter fun stringToDayParts(v: String): Set<DayPart> =
        v.split(',').filter { it.isNotBlank() }.map { DayPart.valueOf(it) }.toSet()

    @TypeConverter fun failReasonsToString(v: Set<FailReason>): String = v.joinToString(",") { it.name }
    @TypeConverter fun stringToFailReasons(v: String): Set<FailReason> =
        v.split(',').filter { it.isNotBlank() }.map { FailReason.valueOf(it) }.toSet()
}
