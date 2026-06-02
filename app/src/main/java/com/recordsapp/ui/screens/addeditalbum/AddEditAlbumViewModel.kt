package com.recordsapp.ui.screens.addeditalbum

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recordsapp.data.local.ImageStorage
import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.entity.CopyEntity
import com.recordsapp.data.remote.ItunesCoverArtService
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
    data class Result(val result: RecognitionResult, val coverArtUrl: String? = null) : RecognitionState()
    data class Error(val message: String) : RecognitionState()
}

data class CopyFormState(
    val id: Long? = null,
    val gradeSide1: Grade? = null,
    val gradeSide2: Grade? = null,
    val country: Country? = null,
    val listened: Boolean = false
)

data class AddEditAlbumState(
    val artistName: String = "",
    val albumName: String = "",
    val numRecords: String = "1",
    val year: String = "",
    val coverImageUri: Uri? = null,
    val comment: String = "",
    val copies: List<CopyFormState> = listOf(CopyFormState()),
    val selectedCopyIndex: Int = 0,
    val removedCopyIds: Set<Long> = emptySet(),
    val showRemoveCopyDialog: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val existingCoverPath: String? = null,
    val recognitionState: RecognitionState = RecognitionState.Idle
)

@HiltViewModel
class AddEditAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AlbumRepository,
    private val imageStorage: ImageStorage,
    private val recognitionService: RecognitionService,
    private val coverArtService: ItunesCoverArtService
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
                    val copyStates = awc.copies.map { copy ->
                        CopyFormState(
                            id = copy.id,
                            gradeSide1 = Grade.entries.find { g -> g.displayName == copy.gradeSide1 },
                            gradeSide2 = Grade.entries.find { g -> g.displayName == copy.gradeSide2 },
                            country = Country.entries.find { c -> c.displayName == copy.country },
                            listened = copy.listened
                        )
                    }.ifEmpty { listOf(CopyFormState()) }
                    _state.value = AddEditAlbumState(
                        artistName = awc.album.artistName,
                        albumName = awc.album.albumName,
                        numRecords = awc.album.numRecords.toString(),
                        year = awc.album.year.toString(),
                        comment = awc.album.comment,
                        existingCoverPath = awc.album.coverImagePath,
                        copies = copyStates,
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

    fun onGradeSide1Changed(grade: Grade?) {
        _state.update { s ->
            val updated = s.copies.toMutableList()
            updated[s.selectedCopyIndex] = updated[s.selectedCopyIndex].copy(gradeSide1 = grade)
            s.copy(copies = updated)
        }
    }

    fun onGradeSide2Changed(grade: Grade?) {
        _state.update { s ->
            val updated = s.copies.toMutableList()
            updated[s.selectedCopyIndex] = updated[s.selectedCopyIndex].copy(gradeSide2 = grade)
            s.copy(copies = updated)
        }
    }

    fun onCountryChanged(country: Country) {
        _state.update { s ->
            val updated = s.copies.toMutableList()
            updated[s.selectedCopyIndex] = updated[s.selectedCopyIndex].copy(country = country)
            s.copy(copies = updated)
        }
    }

    fun onListenedChanged(value: Boolean) {
        _state.update { s ->
            val updated = s.copies.toMutableList()
            updated[s.selectedCopyIndex] = updated[s.selectedCopyIndex].copy(listened = value)
            s.copy(copies = updated)
        }
    }

    fun selectCopy(index: Int) {
        _state.update { it.copy(selectedCopyIndex = index) }
    }

    fun addCopy() {
        _state.update { s ->
            val newCopies = s.copies + CopyFormState()
            s.copy(copies = newCopies, selectedCopyIndex = newCopies.lastIndex)
        }
    }

    fun requestRemoveCopy() {
        _state.update { s ->
            val copy = s.copies[s.selectedCopyIndex]
            if (copy.id != null) {
                s.copy(showRemoveCopyDialog = true)
            } else {
                val newCopies = s.copies.toMutableList().also { it.removeAt(s.selectedCopyIndex) }
                val newIndex = (s.selectedCopyIndex - 1).coerceAtLeast(0)
                s.copy(copies = newCopies, selectedCopyIndex = newIndex)
            }
        }
    }

    fun confirmRemoveCopy() {
        _state.update { s ->
            val index = s.selectedCopyIndex
            val copy = s.copies[index]
            val newRemovedIds = if (copy.id != null) s.removedCopyIds + copy.id else s.removedCopyIds
            val newCopies = s.copies.toMutableList().also { it.removeAt(index) }
            val newIndex = (index - 1).coerceAtLeast(0)
            s.copy(
                copies = newCopies,
                selectedCopyIndex = newIndex,
                removedCopyIds = newRemovedIds,
                showRemoveCopyDialog = false
            )
        }
    }

    fun dismissRemoveCopyDialog() {
        _state.update { it.copy(showRemoveCopyDialog = false) }
    }

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
                if (result.artistName.isNotBlank() && result.albumName.isNotBlank()) {
                    val url = coverArtService.fetchUrl(result.artistName, result.albumName)
                    _state.update { current ->
                        val rs = current.recognitionState
                        if (rs is RecognitionState.Result && rs.result == result) {
                            current.copy(recognitionState = rs.copy(coverArtUrl = url))
                        } else current
                    }
                }
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
        val recognitionResult = _state.value.recognitionState as? RecognitionState.Result ?: return
        val result = recognitionResult.result
        val knownUrl = recognitionResult.coverArtUrl
        _state.update { state ->
            state.copy(
                artistName = result.artistName.ifBlank { state.artistName },
                albumName = result.albumName.ifBlank { state.albumName },
                year = result.year.ifBlank { state.year },
                numRecords = result.numRecords.ifBlank { state.numRecords },
                recognitionState = RecognitionState.Idle
            )
        }
        val artist = result.artistName
        val album = result.albumName
        if (artist.isNotBlank() && album.isNotBlank()) {
            viewModelScope.launch {
                val path = if (knownUrl != null) {
                    coverArtService.fetchUrlAndSave(knownUrl)
                } else {
                    coverArtService.fetchAndSave(artist, album)
                }
                if (path != null) {
                    _state.update { it.copy(coverImageUri = null, existingCoverPath = path) }
                }
            }
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
        if (current.copies.any { it.country == null }) return
        if (current.copies.any { it.listened && (it.gradeSide1 == null || it.gradeSide2 == null) }) return

        _state.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val coverPath = if (current.coverImageUri != null) {
                imageStorage.saveImageFromUri(current.coverImageUri)
            } else {
                current.existingCoverPath
            }

            if (current.isEditing && albumId != null) {
                repository.updateAlbum(
                    AlbumEntity(
                        id = albumId,
                        artistName = current.artistName.trim(),
                        albumName = current.albumName.trim(),
                        numRecords = current.numRecords.toIntOrNull() ?: 1,
                        year = current.year.toIntOrNull() ?: 0,
                        coverImagePath = coverPath,
                        comment = current.comment.trim()
                    )
                )
                for (copy in current.copies) {
                    val entity = CopyEntity(
                        id = copy.id ?: 0,
                        albumId = albumId,
                        gradeSide1 = copy.gradeSide1?.displayName ?: "",
                        gradeSide2 = copy.gradeSide2?.displayName ?: "",
                        country = copy.country!!.displayName,
                        listened = copy.listened
                    )
                    if (copy.id != null) repository.updateCopy(entity)
                    else repository.insertCopy(entity)
                }
                for (removedId in current.removedCopyIds) {
                    repository.deleteCopy(
                        CopyEntity(
                            id = removedId,
                            albumId = albumId,
                            gradeSide1 = "",
                            gradeSide2 = "",
                            country = ""
                        )
                    )
                }
            } else {
                val firstCopy = current.copies.first()
                val newAlbumId = repository.insertAlbumWithCopy(
                    AlbumEntity(
                        artistName = current.artistName.trim(),
                        albumName = current.albumName.trim(),
                        numRecords = current.numRecords.toIntOrNull() ?: 1,
                        year = current.year.toIntOrNull() ?: 0,
                        coverImagePath = coverPath,
                        comment = current.comment.trim()
                    ),
                    CopyEntity(
                        albumId = 0,
                        gradeSide1 = firstCopy.gradeSide1?.displayName ?: "",
                        gradeSide2 = firstCopy.gradeSide2?.displayName ?: "",
                        country = firstCopy.country!!.displayName,
                        listened = firstCopy.listened
                    )
                )
                for (copy in current.copies.drop(1)) {
                    repository.insertCopy(
                        CopyEntity(
                            albumId = newAlbumId,
                            gradeSide1 = copy.gradeSide1?.displayName ?: "",
                            gradeSide2 = copy.gradeSide2?.displayName ?: "",
                            country = copy.country!!.displayName,
                            listened = copy.listened
                        )
                    )
                }
            }
            _state.update { it.copy(isSaving = false) }
            _saveComplete.emit(true)
        }
    }
}
