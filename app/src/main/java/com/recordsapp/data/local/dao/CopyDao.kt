package com.recordsapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.recordsapp.data.local.entity.CopyEntity

@Dao
interface CopyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCopy(copy: CopyEntity): Long

    @Update
    suspend fun updateCopy(copy: CopyEntity)

    @Delete
    suspend fun deleteCopy(copy: CopyEntity)

    @Query("SELECT * FROM copies WHERE albumId = :albumId")
    suspend fun getCopiesForAlbum(albumId: Long): List<CopyEntity>
}
