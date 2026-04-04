package com.recordsapp.ui.screens.albumdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recordsapp.data.local.entity.CopyEntity
import com.recordsapp.data.local.relation.AlbumWithCopies
import com.recordsapp.data.repository.AlbumRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AlbumRepository
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    val albumWithCopies: StateFlow<AlbumWithCopies?> = repository
        .getAlbumWithCopies(albumId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun deleteAlbum() {
        viewModelScope.launch {
            albumWithCopies.value?.let {
                repository.deleteAlbum(it.album)
            }
        }
    }

    fun deleteCopy(copy: CopyEntity) {
        viewModelScope.launch {
            repository.deleteCopy(copy)
        }
    }

    fun toggleListened(copy: CopyEntity) {
        viewModelScope.launch {
            repository.updateCopy(copy.copy(listened = !copy.listened))
        }
    }
}
