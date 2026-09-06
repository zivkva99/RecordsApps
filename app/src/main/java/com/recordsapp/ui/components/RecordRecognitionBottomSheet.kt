package com.recordsapp.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
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
                // Candidates the user has already dismissed with "Not right" —
                // reset whenever a new recognition result comes in.
                var rejectedUrls by remember(recognitionState.result) { mutableStateOf(setOf<String>()) }
                val liveCandidates by remember(recognitionState, rejectedUrls) {
                    derivedStateOf { recognitionState.coverArtUrls.filter { it !in rejectedUrls } }
                }
                // null = no manual override yet (follow the AI's pick as candidates
                // are rejected); a present-but-null url means the user explicitly
                // chose the camera photo over every cover art candidate.
                var manualSelection by remember(recognitionState.result) { mutableStateOf<ManualCoverPick?>(null) }
                val selectedUrl: String? = when (val manual = manualSelection) {
                    null -> liveCandidates.firstOrNull()?.takeIf { recognitionState.bestCoverIsGoodMatch }
                    else -> manual.url
                }

                // null entry = camera photo, string entry = iTunes URL
                val thumbnails: List<String?> = buildList {
                    if (cameraImageUri != null) add(null)
                    addAll(liveCandidates)
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

                    if (cameraImageUri != null && recognitionState.coverArtUrls.isNotEmpty()) {
                        Text(
                            text = "Is this the right cover?",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CoverHeroTile(
                                label = "Your photo",
                                modifier = Modifier.weight(1f)
                            ) {
                                AsyncImage(
                                    model = cameraImageUri,
                                    contentDescription = "Your photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                )
                            }
                            CoverHeroTile(
                                label = if (selectedUrl != null) "AI's suggested cover" else "No confident match",
                                modifier = Modifier.weight(1f)
                            ) {
                                if (selectedUrl != null) {
                                    AsyncImage(
                                        model = selectedUrl,
                                        contentDescription = "AI's suggested cover",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Using your photo",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                                if (recognitionState.isRankingCovers) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                    }
                                }
                            }
                        }
                        if (selectedUrl != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        rejectedUrls = rejectedUrls + selectedUrl
                                        manualSelection = null
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("This isn't right")
                                }
                                Button(
                                    onClick = { onAccept(selectedUrl) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Use this cover")
                                }
                            }
                        }
                    }

                    if (thumbnails.size > 1) {
                        Text(
                            text = "Other options",
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
                                        .clickable { manualSelection = ManualCoverPick(thumbUrl) }
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

/**
 * An explicit user choice in the cover-art hero comparison. Distinct from
 * "no choice made yet" (represented by a null [ManualCoverPick] reference)
 * so that deliberately picking the camera photo (`url == null`) isn't
 * confused with the AI's pick still being followed by default.
 */
private data class ManualCoverPick(val url: String?)

@Composable
private fun CoverHeroTile(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Box(modifier = Modifier.clip(RoundedCornerShape(10.dp))) {
            content()
        }
    }
}
