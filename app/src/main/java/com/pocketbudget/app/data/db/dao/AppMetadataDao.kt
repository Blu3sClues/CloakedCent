package com.pocketbudget.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pocketbudget.app.data.db.entity.AppMetadataEntity

@Dao
interface AppMetadataDao {

    @Query("SELECT meta_value FROM app_metadata WHERE meta_key = :key")
    suspend fun getValue(key: String): String?

    @Upsert
    suspend fun upsert(entry: AppMetadataEntity)
}
