package com.recordsapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.relation.AlbumWithCopies
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    @Transaction
    @Query("SELECT * FROM albums ORDER BY artistName ASC, albumName ASC")
    fun getAllAlbumsWithCopies(): Flow<List<AlbumWithCopies>>

    @Transaction
    @Query("SELECT * FROM albums WHERE id = :albumId")
    fun getAlbumWithCopies(albumId: Long): Flow<AlbumWithCopies?>

    @Transaction
    @Query(
        """
        SELECT * FROM albums
        WHERE artistName LIKE '%' || :query || '%'
        OR albumName LIKE '%' || :query || '%'
        ORDER BY artistName ASC, albumName ASC
        """
    )
    fun searchAlbums(query: String): Flow<List<AlbumWithCopies>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Update
    suspend fun updateAlbum(album: AlbumEntity)

    @Delete
    suspend fun deleteAlbum(album: AlbumEntity)

    @Query("DELETE FROM albums")
    suspend fun deleteAllAlbums()

    @Query("SELECT COUNT(*) FROM albums WHERE artistName = :artist AND albumName = :album")
    suspend fun countByArtistAndAlbum(artist: String, album: String): Int
}
