package com.cloakedcent.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Key/value facts about this installation's database (creation time, future backup info). */
@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "meta_key")
    val key: String,
    @ColumnInfo(name = "meta_value")
    val value: String,
)
