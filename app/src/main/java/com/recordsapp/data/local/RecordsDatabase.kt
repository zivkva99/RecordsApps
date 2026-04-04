package com.recordsapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.recordsapp.data.local.dao.AlbumDao
import com.recordsapp.data.local.dao.CopyDao
import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.entity.CopyEntity

@Database(
    entities = [AlbumEntity::class, CopyEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RecordsDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun copyDao(): CopyDao
}
