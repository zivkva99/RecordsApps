package com.recordsapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artistName: String,
    val albumName: String,
    val numRecords: Int,
    val year: Int,
    val coverImagePath: String? = null,
    val comment: String = ""
)
