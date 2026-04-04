package com.recordsapp.ui.screens.addcopy

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recordsapp.data.local.entity.CopyEntity
import com.recordsapp.data.repository.AlbumRepository
import com.recordsapp.domain.model.Country
import com.recordsapp.domain.model.Grade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddCopyState(
    val gradeSide1: Grade? = null,
    val gradeSide2: Grade? = null,
    val country: Country? = null,
    val listened: Boolean = false,
    val isSaving: Boolean = false
)

@HiltViewModel
class AddCopyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AlbumRepository
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    private val _state = MutableStateFlow(AddCopyState())
    val state: StateFlow<AddCopyState> = _state.asStateFlow()

    private val _saveComplete = MutableSharedFlow<Boolean>()
    val saveComplete: SharedFlow<Boolean> = _saveComplete.asSharedFlow()

    fun onGradeSide1Changed(grade: Grade) { _state.update { it.copy(gradeSide1 = grade) } }
    fun onGradeSide2Changed(grade: Grade) { _state.update { it.copy(gradeSide2 = grade) } }
    fun onCountryChanged(country: Country) { _state.update { it.copy(country = country) } }
    fun onListenedChanged(value: Boolean) { _state.update { it.copy(listened = value) } }

    fun save() {
        val current = _state.value
        if (current.gradeSide1 == null || current.gradeSide2 == null || current.country == null) return

        _state.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val copy = CopyEntity(
                albumId = albumId,
                gradeSide1 = current.gradeSide1.displayName,
                gradeSide2 = current.gradeSide2.displayName,
                country = current.country.displayName,
                listened = current.listened
            )
            repository.insertCopy(copy)
            _state.update { it.copy(isSaving = false) }
            _saveComplete.emit(true)
        }
    }
}
