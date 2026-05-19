package com.recordsapp.ui.screens.addeditalbum

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recordsapp.ui.components.CountryDropdown
import com.recordsapp.ui.components.CoverImagePicker
import com.recordsapp.ui.components.GradeDropdown
import com.recordsapp.ui.components.NumRecordsDropdown
import com.recordsapp.ui.components.RecordRecognitionBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditAlbumScreen(
    onNavigateBack: () -> Unit,
    viewModel: AddEditAlbumViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var launchCamera by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.saveComplete.collect { success ->
            if (success) onNavigateBack()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.retakeRequested.collect {
            launchCamera = true
        }
    }

    if (state.recognitionState != RecognitionState.Idle) {
        RecordRecognitionBottomSheet(
            recognitionState = state.recognitionState,
            onAccept = viewModel::acceptRecognition,
            onReject = viewModel::rejectRecognition,
            onRetake = viewModel::retakePhoto
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit Album" else "Add Album") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CoverImagePicker(
                currentImageUri = state.coverImageUri
                    ?: state.existingCoverPath?.let { Uri.parse(it) },
                onImagePicked = viewModel::onCoverImageChanged,
                launchCamera = launchCamera,
                onCameraLaunched = { launchCamera = false }
            )

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = state.artistName,
                onValueChange = viewModel::onArtistNameChanged,
                label = { Text("Artist Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = state.albumName,
                onValueChange = viewModel::onAlbumNameChanged,
                label = { Text("Album Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NumRecordsDropdown(
                    selectedValue = state.numRecords,
                    onValueSelected = viewModel::onNumRecordsChanged,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.year,
                    onValueChange = viewModel::onYearChanged,
                    label = { Text("Year") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = state.comment,
                onValueChange = viewModel::onCommentChanged,
                label = { Text("Comment") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5
            )

            if (!state.isEditing) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "First Copy Details",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )

                GradeDropdown(
                    label = "Grade Side 1 *",
                    selectedGrade = state.gradeSide1,
                    onGradeSelected = viewModel::onGradeSide1Changed,
                    modifier = Modifier.fillMaxWidth()
                )

                GradeDropdown(
                    label = "Grade Side 2 *",
                    selectedGrade = state.gradeSide2,
                    onGradeSelected = viewModel::onGradeSide2Changed,
                    modifier = Modifier.fillMaxWidth()
                )

                CountryDropdown(
                    selectedCountry = state.country,
                    onCountrySelected = viewModel::onCountryChanged,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Listened",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.listened,
                        onCheckedChange = viewModel::onListenedChanged
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving &&
                    state.artistName.isNotBlank() &&
                    state.albumName.isNotBlank() &&
                    (state.isEditing || (
                        state.gradeSide1 != null &&
                            state.gradeSide2 != null &&
                            state.country != null
                        ))
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (state.isEditing) "Save Changes" else "Add Album")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
