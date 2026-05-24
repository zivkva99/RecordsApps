package com.recordsapp.ui.screens.addeditalbum

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recordsapp.data.local.ImageStorage
import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.entity.CopyEntity
import com.recordsapp.data.remote.RecognitionApiException
import com.recordsapp.data.remote.RecognitionService
import com.recordsapp.data.repository.AlbumRepository
import com.recordsapp.domain.model.Country
import com.recordsapp.domain.model.Grade
import com.recordsapp.domain.model.RecognitionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

sealed class RecognitionState {
    object Idle : RecognitionState()
    object Loading : RecognitionState()
    data class Result(val result: RecognitionResult) : RecognitionState()
    data class Error(val message: String) : RecognitionState()
}

data class AddEditAlbumState(
    val artistName: String = "",
    val albumName: String = "",
    val numRecords: String = "1",
    val year: String = "",
    val coverImageUri: Uri? = null,
    val comment: String = "",
    val gradeSide1: Grade? = null,
    val gradeSide2: Grade? = null,
    val country: Country? = null,
    val listened: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val existingCoverPath: String? = null,
    val copyId: Long? = null,
    val recognitionState: RecognitionState = RecognitionState.Idle
)

@HiltViewModel
class AddEditAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AlbumRepository,
    private val imageStorage: ImageStorage,
    private val recognitionService: RecognitionService
) : ViewModel() {

    private val albumId: Long? = savedStateHandle.get<Long>("albumId")

    private val _state = MutableStateFlow(AddEditAlbumState())
    val state: StateFlow<AddEditAlbumState> = _state.asStateFlow()

    private val _saveComplete = MutableSharedFlow<Boolean>()
    val saveComplete: SharedFlow<Boolean> = _saveComplete.asSharedFlow()

    private val _retakeRequested = MutableSharedFlow<Unit>()
    val retakeRequested: SharedFlow<Unit> = _retakeRequested.asSharedFlow()

    private var recognitionJob: Job? = null

    init {
        if (albumId != null) {
            viewModelScope.launch {
                repository.getAlbumWithCopies(albumId).first()?.let { awc ->
                    val firstCopy = awc.copies.firstOrNull()
                    _state.value = AddEditAlbumState(
                        artistName = awc.album.artistName,
                        albumName = awc.album.albumName,
                        numRecords = awc.album.numRecords.toString(),
                        year = awc.album.year.toString(),
                        comment = awc.album.comment,
                        existingCoverPath = awc.album.coverImagePath,
                        copyId = firstCopy?.id,
                        gradeSide1 = firstCopy?.let {
                            Grade.entries.find { g -> g.displayName == it.gradeSide1 }
                        },
                        gradeSide2 = firstCopy?.let {
                            Grade.entries.find { g -> g.displayName == it.gradeSide2 }
                        },
                        country = firstCopy?.let {
                            Country.entries.find { c -> c.displayName == it.country }
                        },
                        listened = firstCopy?.listened ?: false,
                        isEditing = true
                    )
                }
            }
        }
    }

    fun onArtistNameChanged(value: String) { _state.update { it.copy(artistName = value) } }
    fun onAlbumNameChanged(value: String) { _state.update { it.copy(albumName = value) } }
    fun onNumRecordsChanged(value: String) { _state.update { it.copy(numRecords = value) } }
    fun onYearChanged(value: String) { _state.update { it.copy(year = value) } }
    fun onCommentChanged(value: String) { _state.update { it.copy(comment = value) } }
    fun onGradeSide1Changed(grade: Grade?) { _state.update { it.copy(gradeSide1 = grade) } }
    fun onGradeSide2Changed(grade: Grade?) { _state.update { it.copy(gradeSide2 = grade) } }
    fun onCountryChanged(country: Country) { _state.update { it.copy(country = country) } }
    fun onListenedChanged(value: Boolean) { _state.update { it.copy(listened = value) } }

    fun onCoverImageChanged(uri: Uri) {
        _state.update { it.copy(coverImageUri = uri) }
        recognizeRecord(uri)
    }

    private fun recognizeRecord(uri: Uri) {
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            _state.update { it.copy(recognitionState = RecognitionState.Loading) }
            try {
                val result = recognitionService.recognize(uri)
                _state.update { it.copy(recognitionState = RecognitionState.Result(result)) }
            } catch (e: Exception) {
                val message = when {
                    e is UnknownHostException -> "No internet connection. Retake or fill manually."
                    e is SocketTimeoutException -> "Recognition timed out."
                    e is RecognitionApiException -> "${e.code}: ${e.body.take(400)}"
                    else -> "${e.javaClass.simpleName}: ${e.message}"
                }
                _state.update { it.copy(recognitionState = RecognitionState.Error(message)) }
            }
        }
    }

    fun acceptRecognition() {
        val result = (_state.value.recognitionState as? RecognitionState.Result)?.result ?: return
        _state.update { state ->
            state.copy(
                artistName = result.artistName.ifBlank { state.artistName },
                albumName = result.albumName.ifBlank { state.albumName },
                year = result.year.ifBlank { state.year },
                numRecords = result.numRecords.ifBlank { state.numRecords },
                recognitionState = RecognitionState.Idle
            )
        }
    }

    fun rejectRecognition() {
        recognitionJob?.cancel()
        _state.update { it.copy(recognitionState = RecognitionState.Idle) }
    }

    fun retakePhoto() {
        recognitionJob?.cancel()
        _state.update { it.copy(recognitionState = RecognitionState.Idle, coverImageUri = null) }
        viewModelScope.launch { _retakeRequested.emit(Unit) }
    }

    fun save() {
        val current = _state.value
        if (current.artistName.isBlank() || current.albumName.isBlank()) return
        if (current.country == null) return
        if (current.listened && (current.gradeSide1 == null || current.gradeSide2 == null)) return

        _state.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val coverPath = if (current.coverImageUri != null) {
                imageStorage.saveImageFromUri(current.coverImageUri)
            } else {
                current.existingCoverPath
            }

            if (current.isEditing && albumId != null) {
                val album = AlbumEntity(
                    id = albumId,
                    artistName = current.artistName.trim(),
                    albumName = current.albumName.trim(),
                    numRecords = current.numRecords.toIntOrNull() ?: 1,
                    year = current.year.toIntOrNull() ?: 0,
                    coverImagePath = coverPath,
                    comment = current.comment.trim()
                )
                repository.updateAlbum(album)
                val copyId = current.copyId
                if (copyId != null) {
                    repository.updateCopy(
                        CopyEntity(
                            id = copyId,
                            albumId = albumId,
                            gradeSide1 = current.gradeSide1?.displayName ?: "",
                            gradeSide2 = current.gradeSide2?.displayName ?: "",
                            country = current.country.displayName,
                            listened = current.listened
                        )
                    )
                }
            } else {
                val album = AlbumEntity(
                    artistName = current.artistName.trim(),
                    albumName = current.albumName.trim(),
                    numRecords = current.numRecords.toIntOrNull() ?: 1,
                    year = current.year.toIntOrNull() ?: 0,
                    coverImagePath = coverPath,
                    comment = current.comment.trim()
                )
                val copy = CopyEntity(
                    albumId = 0,
                    gradeSide1 = current.gradeSide1?.displayName ?: "",
                    gradeSide2 = current.gradeSide2?.displayName ?: "",
                    country = current.country.displayName,
                    listened = current.listened
                )
                repository.insertAlbumWithCopy(album, copy)
            }
            _state.update { it.copy(isSaving = false) }
            _saveComplete.emit(true)
        }
    }
}
