package com.recordsapp.data.repository

import com.recordsapp.data.local.dao.AlbumDao
import com.recordsapp.data.local.dao.CopyDao
import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.entity.CopyEntity
import com.recordsapp.data.local.relation.AlbumWithCopies
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepository @Inject constructor(
    private val albumDao: AlbumDao,
    private val copyDao: CopyDao
) {
    fun getAllAlbumsWithCopies(): Flow<List<AlbumWithCopies>> =
        albumDao.getAllAlbumsWithCopies()

    fun getAlbumWithCopies(albumId: Long): Flow<AlbumWithCopies?> =
        albumDao.getAlbumWithCopies(albumId)

    fun searchAlbums(query: String): Flow<List<AlbumWithCopies>> =
        albumDao.searchAlbums(query)

    suspend fun insertAlbumWithCopy(album: AlbumEntity, copy: CopyEntity): Long {
        val albumId = albumDao.insertAlbum(album)
        copyDao.insertCopy(copy.copy(albumId = albumId))
        return albumId
    }

    suspend fun updateAlbum(album: AlbumEntity) =
        albumDao.updateAlbum(album)

    suspend fun deleteAlbum(album: AlbumEntity) =
        albumDao.deleteAlbum(album)

    suspend fun insertCopy(copy: CopyEntity): Long =
        copyDao.insertCopy(copy)

    suspend fun updateCopy(copy: CopyEntity) =
        copyDao.updateCopy(copy)

    suspend fun deleteCopy(copy: CopyEntity) =
        copyDao.deleteCopy(copy)
}
