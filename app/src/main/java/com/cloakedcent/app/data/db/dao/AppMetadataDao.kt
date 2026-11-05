package com.cloakedcent.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.cloakedcent.app.data.db.entity.AppMetadataEntity

@Dao
interface AppMetadataDao {

    @Query("SELECT meta_value FROM app_metadata WHERE meta_key = :key")
    suspend fun getValue(key: String): String?

    @Upsert
    suspend fun upsert(entry: AppMetadataEntity)

    /** Never replaces an existing value (used for keys that must not change once written). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: AppMetadataEntity)
}
