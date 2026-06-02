# Cover Art Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show all iTunes cover art options plus the user's camera photo as selectable thumbnails in the recognition bottom sheet.

**Architecture:** `ItunesCoverArtService` gains `fetchUrls()` returning all results. `RecognitionState.Result` stores a `List<String>` instead of a single URL. The bottom sheet renders a `LazyRow` of tappable thumbnails (camera photo + iTunes URLs); local selection state drives the `onAccept(String?)` callback. The ViewModel's `acceptRecognition` is updated to handle the selected URL or null (camera).

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp, Coil 3, Hilt

---

### Task 1: Add `fetchUrls()` to ItunesCoverArtService

**Files:**
- Modify: `app/src/main/java/com/recordsapp/data/remote/ItunesCoverArtService.kt`

- [ ] **Step 1: Add `fetchUrls()` and `parseArtworkUrls()`**

Add the two functions below the existing `fetchUrl` function. Do not remove any existing functions.

```kotlin
suspend fun fetchUrls(artist: String, album: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val query = URLEncoder.encode("$artist $album", "UTF-8")
        val searchReq = Request.Builder()
            .url("https://itunes.apple.com/search?term=$query&media=music&entity=album&limit=5")
            .build()
        client.newCall(searchReq).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            parseArtworkUrls(body)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun parseArtworkUrls(json: String): List<String> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    val urls = mutableListOf<String>()
    for (i in 0 until results.length()) {
        val url = results.getJSONObject(i).optString("artworkUrl100").ifBlank { continue }
        urls.add(url.replace("100x100bb", "600x600bb"))
    }
    return urls
}
```

- [ ] **Step 2: Build to verify compilation**

```
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

---

### Task 2: Update ViewModel — RecognitionState.Result, recognizeRecord, acceptRecognition

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModel.kt`

- [ ] **Step 1: Replace `coverArtUrl` with `coverArtUrls` in `RecognitionState.Result`**

Find:
```kotlin
data class Result(val result: RecognitionResult, val coverArtUrl: String? = null) : RecognitionState()
```

Replace with:
```kotlin
data class Result(val result: RecognitionResult, val coverArtUrls: List<String> = emptyList()) : RecognitionState()
```

- [ ] **Step 2: Update `recognizeRecord()` to call `fetchUrls()`**

Find this block inside `recognizeRecord()`:
```kotlin
if (result.artistName.isNotBlank() && result.albumName.isNotBlank()) {
    val url = coverArtService.fetchUrl(result.artistName, result.albumName)
    _state.update { current ->
        val rs = current.recognitionState
        if (rs is RecognitionState.Result && rs.result == result) {
            current.copy(recognitionState = rs.copy(coverArtUrl = url))
        } else current
    }
}
```

Replace with:
```kotlin
if (result.artistName.isNotBlank() && result.albumName.isNotBlank()) {
    val urls = coverArtService.fetchUrls(result.artistName, result.albumName)
    _state.update { current ->
        val rs = current.recognitionState
        if (rs is RecognitionState.Result && rs.result == result) {
            current.copy(recognitionState = rs.copy(coverArtUrls = urls))
        } else current
    }
}
```

- [ ] **Step 3: Replace `acceptRecognition()` with the new signature**

Find:
```kotlin
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
```

Replace with:
```kotlin
fun acceptRecognition(selectedCoverUrl: String?) {
    val recognitionResult = _state.value.recognitionState as? RecognitionState.Result ?: return
    val result = recognitionResult.result
    _state.update { state ->
        state.copy(
            artistName = result.artistName.ifBlank { state.artistName },
            albumName = result.albumName.ifBlank { state.albumName },
            year = result.year.ifBlank { state.year },
            numRecords = result.numRecords.ifBlank { state.numRecords },
            recognitionState = RecognitionState.Idle
        )
    }
    if (selectedCoverUrl != null) {
        viewModelScope.launch {
            val path = coverArtService.fetchUrlAndSave(selectedCoverUrl)
            if (path != null) {
                _state.update { it.copy(coverImageUri = null, existingCoverPath = path) }
            }
        }
    }
    // selectedCoverUrl == null → camera photo (coverImageUri) stays as-is
}
```

- [ ] **Step 4: Build to verify compilation**

```
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL (the Screen will have a compile error on the `onAccept` call until Task 4 — fix it in Task 3 and 4).

---

### Task 3: Update RecordRecognitionBottomSheet — thumbnail row + new onAccept signature

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/components/RecordRecognitionBottomSheet.kt`

- [ ] **Step 1: Add missing imports**

Add these imports (any not already present):
```kotlin
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
```

- [ ] **Step 2: Replace the entire file content**

Replace `RecordRecognitionBottomSheet.kt` with:

```kotlin
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                // thumbnails: null entry = camera photo, string entry = iTunes URL
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
                                val borderColor = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    Color.Transparent
                                AsyncImage(
                                    model = thumbUrl ?: cameraImageUri,
                                    contentDescription = if (thumbUrl == null) "Your photo" else "Cover art option",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(2.dp, borderColor, RoundedCornerShape(8.dp))
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
```

---

### Task 4: Update AddEditAlbumScreen — pass cameraImageUri and new onAccept lambda

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumScreen.kt`

- [ ] **Step 1: Update the RecordRecognitionBottomSheet call**

Find:
```kotlin
RecordRecognitionBottomSheet(
    recognitionState = state.recognitionState,
    onAccept = viewModel::acceptRecognition,
    onReject = viewModel::rejectRecognition,
    onRetake = viewModel::retakePhoto
)
```

Replace with:
```kotlin
RecordRecognitionBottomSheet(
    recognitionState = state.recognitionState,
    cameraImageUri = state.coverImageUri,
    onAccept = { url -> viewModel.acceptRecognition(url) },
    onReject = viewModel::rejectRecognition,
    onRetake = viewModel::retakePhoto
)
```

- [ ] **Step 2: Build and install**

```
./gradlew installDebug
```

Expected: BUILD SUCCESSFUL, installed on device.

- [ ] **Step 3: Validate**

Take a photo of a record. Wait for recognition to complete. In the result bottom sheet:
- Verify a "Choose cover art" label appears above a horizontal row of thumbnails.
- Verify the first thumbnail (if iTunes returned results) has a colored border indicating it's selected.
- Verify the camera photo appears as the first thumbnail (no border by default since an iTunes result is pre-selected).
- Tap the camera photo thumbnail — verify its border becomes active and the iTunes thumbnail loses it.
- Tap Accept — verify the camera photo is used as cover art.
- Repeat: take another photo, this time tap an iTunes thumbnail other than the default. Accept — verify that cover art is applied.

- [ ] **Step 4: Commit and push**

```bash
git add app/src/main/java/com/recordsapp/data/remote/ItunesCoverArtService.kt
git add app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModel.kt
git add app/src/main/java/com/recordsapp/ui/components/RecordRecognitionBottomSheet.kt
git add app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumScreen.kt
git add docs/superpowers/plans/2026-06-02-cover-art-picker.md
git commit -m "feat: show cover art picker with all iTunes options and camera photo"
git push
```
