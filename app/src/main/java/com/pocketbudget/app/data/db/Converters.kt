package com.pocketbudget.app.data.db

import androidx.room.TypeConverter
import java.time.LocalDate

/** Calendar dates are stored as epoch days (INTEGER), independent of time zone. */
class Converters {
    @TypeConverter
    fun toEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun fromEpochDay(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)
}
