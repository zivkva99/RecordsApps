package com.recordsapp.ui.components

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.recordsapp.domain.model.Confidence
import com.recordsapp.ui.screens.addeditalbum.RecognitionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordRecognitionBottomSheet(
    recognitionState: RecognitionState,
    cameraImageUri: Uri?,
    onAccept: (String?) -> Unit,
    onReject: () -> Unit,
    onRetake: () -> Unit
) {
    if (recognitionState == RecognitionState.Idle) return

    ModalBottomSheet(
        onDismissRequest = onReject,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // Swiping/back/scrim must not silently discard recognized data —
            // only the explicit Accept/Reject/Retake buttons may close the sheet.
            confirmValueChange = { it != SheetValue.Hidden }
        )
    ) {
        when (recognitionState) {
            is RecognitionState.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Identifying record…", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            is RecognitionState.Result -> {
                // null entry = camera photo, string entry = iTunes URL
                val thumbnails: List<String?> = buildList {
                    if (cameraImageUri != null) add(null)
                    addAll(recognitionState.coverArtUrls)
                }
                var selectedUrl by remember(recognitionState) {
                    mutableStateOf(recognitionState.coverArtUrls.firstOrNull())
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Record Identified", style = MaterialTheme.typography.titleLarge)
                            if (recognitionState.result.confidence == Confidence.LOW) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "Low confidence — please verify",
                                        color = MaterialTheme.colorScheme.tertiary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    RecognitionField("Artist", recognitionState.result.artistName)
                    RecognitionField("Album", recognitionState.result.albumName)
                    RecognitionField("Year", recognitionState.result.year)
                    RecognitionField("Records", recognitionState.result.numRecords)

                    if (thumbnails.isNotEmpty()) {
                        Text(
                            text = "Choose cover art",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(thumbnails) { thumbUrl ->
                                val isSelected = thumbUrl == selectedUrl
                                AsyncImage(
                                    model = thumbUrl ?: cameraImageUri,
                                    contentDescription = if (thumbUrl == null) "Your photo" else "Cover art option",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            width = 2.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { selectedUrl = thumbUrl }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
                            Text("Retake")
                        }
                        OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                            Text("Reject")
                        }
                        Button(
                            onClick = { onAccept(selectedUrl) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Accept")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            is RecognitionState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Couldn't identify record", style = MaterialTheme.typography.titleLarge)
                    Text(recognitionState.message, style = MaterialTheme.typography.bodyMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
                            Text("Retake")
                        }
                        OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                            Text("Fill Manually")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            RecognitionState.Idle -> {}
        }
    }
}

@Composable
private fun RecognitionField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}
