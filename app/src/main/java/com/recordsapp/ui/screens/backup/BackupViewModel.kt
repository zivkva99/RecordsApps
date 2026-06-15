package com.recordsapp.ui.screens.backup

import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recordsapp.data.drive.BackupSerializer
import com.recordsapp.data.drive.DriveAuthManager
import com.recordsapp.data.drive.DriveBackupRepository
import com.recordsapp.data.local.ImageStorage
import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.entity.CopyEntity
import com.recordsapp.data.repository.AlbumRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed class RestoreMode {
    object Replace : RestoreMode()
    object Merge : RestoreMode()
}

data class BackupState(
    val accountEmail: String? = null,
    val lastBackupTime: String? = null,
    val isOperationInProgress: Boolean = false,
    val statusMessage: String = "",
    val showRestoreDialog: Boolean = false,
    val snackbarMessage: String? = null
)

private const val PREF_LAST_BACKUP = "last_backup_time"

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    private val imageStorage: ImageStorage,
    private val serializer: BackupSerializer,
    private val authManager: DriveAuthManager,
    private val driveRepository: DriveBackupRepository,
    private val prefs: SharedPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(
        BackupState(
            accountEmail = authManager.currentAccountEmail(),
            lastBackupTime = prefs.getString(PREF_LAST_BACKUP, null)
        )
    )
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun signInIntent(): Intent = authManager.signInIntent()

    fun onSignInResult(resultCode: Int, data: Intent?) {
        val account = authManager.handleSignInResult(data)
        _state.update { it.copy(accountEmail = account?.email) }
    }

    fun onSignOut() {
        viewModelScope.launch {
            authManager.signOut()
            _state.update { it.copy(accountEmail = null) }
        }
    }

    fun onBackupClick() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, statusMessage = "") }
            try {
                val token = authManager.accessToken()
                val albums = albumRepository.getAllAlbumsWithCopiesOnce()
                val json = serializer.serialize(albums)
                val coverImages = albums
                    .mapNotNull { it.album.coverImagePath }
                    .associate { path -> File(path).name to File(path).readBytes() }

                driveRepository.backup(token, json, coverImages) { msg ->
                    _state.update { it.copy(statusMessage = msg) }
                }

                val timestamp = SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault())
                    .format(Date())
                prefs.edit().putString(PREF_LAST_BACKUP, timestamp).apply()
                _state.update {
                    it.copy(
                        isOperationInProgress = false,
                        lastBackupTime = timestamp,
                        snackbarMessage = "Backup complete"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isOperationInProgress = false,
                        snackbarMessage = "Backup failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun onRestoreClick() {
        _state.update { it.copy(showRestoreDialog = true) }
    }

    fun onRestoreConfirmed(mode: RestoreMode) {
        _state.update { it.copy(showRestoreDialog = false) }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, statusMessage = "") }
            try {
                val token = authManager.accessToken()
                val result = driveRepository.restore(token) { msg ->
                    _state.update { it.copy(statusMessage = msg) }
                }
                val backupAlbums = serializer.deserialize(result.json)

                // Pre-compute which albums to insert (for Merge, filter out existing)
                val albumsToInsert = if (mode is RestoreMode.Merge) {
                    backupAlbums.filter { !albumRepository.albumExists(it.artist, it.albumName) }
                } else {
                    backupAlbums
                }

                // Save cover images to local storage before touching the DB.
                // Per-image try-catch: a single failed image does not abort the restore.
                _state.update { it.copy(statusMessage = "Saving cover images…") }
                val coverPaths = albumsToInsert.associate { backupAlbum ->
                    val path = backupAlbum.coverImageFile?.let { filename ->
                        result.coverImages[filename]?.let { bytes ->
                            try { imageStorage.saveImageFromBytes(bytes) } catch (e: Exception) { null }
                        }
                    }
                    backupAlbum to path
                }

                // Wrap delete + insert in a single Room transaction so the DB is never
                // left partially cleared if an insert fails mid-way (Replace path only).
                var insertedCount = 0
                albumRepository.withTransaction {
                    if (mode is RestoreMode.Replace) albumRepository.deleteAll()

                    albumsToInsert.forEachIndexed { index, backupAlbum ->
                        _state.update {
                            it.copy(statusMessage = "Restoring album ${index + 1} / ${albumsToInsert.size}…")
                        }
                        val coverPath = coverPaths[backupAlbum]
                        val albumEntity = AlbumEntity(
                            artistName = backupAlbum.artist,
                            albumName = backupAlbum.albumName,
                            numRecords = backupAlbum.numRecords,
                            year = backupAlbum.year,
                            comment = backupAlbum.comment,
                            coverImagePath = coverPath
                        )
                        val firstCopy = backupAlbum.copies.firstOrNull() ?: return@forEachIndexed
                        val albumId = albumRepository.insertAlbumWithCopy(
                            albumEntity,
                            CopyEntity(
                                albumId = 0,
                                gradeSide1 = firstCopy.gradeSide1,
                                gradeSide2 = firstCopy.gradeSide2,
                                country = firstCopy.country,
                                listened = firstCopy.listened
                            )
                        )
                        backupAlbum.copies.drop(1).forEach { copy ->
                            albumRepository.insertCopy(
                                CopyEntity(
                                    albumId = albumId,
                                    gradeSide1 = copy.gradeSide1,
                                    gradeSide2 = copy.gradeSide2,
                                    country = copy.country,
                                    listened = copy.listened
                                )
                            )
                        }
                        insertedCount++
                    }
                } // end withTransaction

                _state.update {
                    it.copy(
                        isOperationInProgress = false,
                        snackbarMessage = "Restored $insertedCount album${if (insertedCount != 1) "s" else ""}"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isOperationInProgress = false,
                        snackbarMessage = "Restore failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun onRestoreDismissed() {
        _state.update { it.copy(showRestoreDialog = false) }
    }

    fun onSnackbarDismissed() {
        _state.update { it.copy(snackbarMessage = null) }
    }
}
