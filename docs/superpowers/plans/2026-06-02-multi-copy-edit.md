# Multi-Copy Edit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to add, switch between, and remove copies directly within AddEditAlbumScreen for both add and edit flows.

**Architecture:** `AddEditAlbumState` replaces flat copy fields with a `List<CopyFormState>` + `selectedCopyIndex`. The ViewModel gains copy-management functions. The Screen gains a copy-selector dropdown, Add Copy / Remove Copy buttons, and a confirmation dialog. No DB schema changes — only the ViewModel and Screen files change.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, StateFlow

**Safety invariant:** Only copy IDs in `removedCopyIds` (populated by explicit user confirmation) are ever deleted. No implicit deletion based on set difference.

---

### Task 1: Replace flat copy fields with CopyFormState list in ViewModel

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModel.kt`

- [ ] **Step 1: Add `CopyFormState` data class and rewrite `AddEditAlbumState`**

In `AddEditAlbumViewModel.kt`, add `CopyFormState` immediately before `AddEditAlbumState`, then replace `AddEditAlbumState` as shown. Remove fields: `gradeSide1`, `gradeSide2`, `country`, `listened`, `copyId`. Add: `copies`, `selectedCopyIndex`, `removedCopyIds`, `showRemoveCopyDialog`.

```kotlin
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
```

- [ ] **Step 2: Update `init` to load all copies**

Replace the existing `init` block:

```kotlin
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
```

- [ ] **Step 3: Replace field-change functions to operate on `copies[selectedCopyIndex]`**

Replace `onGradeSide1Changed`, `onGradeSide2Changed`, `onCountryChanged`, `onListenedChanged`:

```kotlin
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
```

- [ ] **Step 4: Add copy-management functions**

Add these after `onListenedChanged`:

```kotlin
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
```

- [ ] **Step 5: Rewrite `save()`**

Replace the entire `save()` function:

```kotlin
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
                    CopyEntity(id = removedId, albumId = albumId, gradeSide1 = "", gradeSide2 = "", country = "")
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
```

- [ ] **Step 6: Build to verify compilation**

```
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL (Screen will have compile errors until Task 2 — that's OK if you haven't touched it yet; fix those in Task 2).

---

### Task 2: Rewrite the copy-details section in AddEditAlbumScreen

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumScreen.kt`

- [ ] **Step 1: Replace the copy-details section**

Find this block in the Screen (lines 144–184 approximately):

```kotlin
HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
Text(
    text = if (state.isEditing) "Copy Details" else "First Copy Details",
    ...
)
GradeDropdown( ... gradeSide1 ... )
GradeDropdown( ... gradeSide2 ... )
CountryDropdown( ... )
Row( ... Listened ... Switch ... )
```

Replace it entirely with:

```kotlin
HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

val selectedCopy = state.copies[state.selectedCopyIndex]
var copyDropdownExpanded by remember { mutableStateOf(false) }

Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text(
        text = "Copy Details",
        style = MaterialTheme.typography.titleMedium
    )
    Box {
        TextButton(onClick = { copyDropdownExpanded = true }) {
            Text("Copy ${state.selectedCopyIndex + 1} of ${state.copies.size}")
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null
            )
        }
        DropdownMenu(
            expanded = copyDropdownExpanded,
            onDismissRequest = { copyDropdownExpanded = false }
        ) {
            state.copies.forEachIndexed { index, _ ->
                DropdownMenuItem(
                    text = { Text("Copy ${index + 1}") },
                    onClick = {
                        viewModel.selectCopy(index)
                        copyDropdownExpanded = false
                    }
                )
            }
        }
    }
}

GradeDropdown(
    label = if (selectedCopy.listened) "Grade Side 1 *" else "Grade Side 1",
    selectedGrade = selectedCopy.gradeSide1,
    onGradeSelected = viewModel::onGradeSide1Changed,
    modifier = Modifier.fillMaxWidth()
)

GradeDropdown(
    label = if (selectedCopy.listened) "Grade Side 2 *" else "Grade Side 2",
    selectedGrade = selectedCopy.gradeSide2,
    onGradeSelected = viewModel::onGradeSide2Changed,
    modifier = Modifier.fillMaxWidth()
)

CountryDropdown(
    selectedCountry = selectedCopy.country,
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
        checked = selectedCopy.listened,
        onCheckedChange = viewModel::onListenedChanged
    )
}

Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    TextButton(onClick = viewModel::addCopy) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(4.dp))
        Text("Add Copy")
    }
    if (state.copies.size > 1) {
        TextButton(onClick = viewModel::requestRemoveCopy) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Remove Copy", color = MaterialTheme.colorScheme.error)
        }
    }
}
```

- [ ] **Step 2: Update the save-button `enabled` condition**

Find:
```kotlin
enabled = !state.isSaving &&
    state.artistName.isNotBlank() &&
    state.albumName.isNotBlank() &&
    state.country != null &&
    (!state.listened || (state.gradeSide1 != null && state.gradeSide2 != null))
```

Replace with:
```kotlin
enabled = !state.isSaving &&
    state.artistName.isNotBlank() &&
    state.albumName.isNotBlank() &&
    state.copies.all { it.country != null } &&
    state.copies.none { it.listened && (it.gradeSide1 == null || it.gradeSide2 == null) }
```

- [ ] **Step 3: Add the confirmation dialog**

Add this block just before the existing `RecordRecognitionBottomSheet` check at the bottom of the composable (outside the Scaffold):

```kotlin
if (state.showRemoveCopyDialog) {
    AlertDialog(
        onDismissRequest = viewModel::dismissRemoveCopyDialog,
        title = { Text("Delete Copy #${state.selectedCopyIndex + 1}?") },
        text = { Text("This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = viewModel::confirmRemoveCopy) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissRemoveCopyDialog) {
                Text("Cancel")
            }
        }
    )
}
```

- [ ] **Step 4: Add missing imports**

Add to the imports block (any that aren't already present):

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
```

- [ ] **Step 5: Build**

```
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL with no errors (deprecation warning on `menuAnchor` is pre-existing, not introduced here).

---

### Task 3: Install and validate

- [ ] **Step 1: Install on device**

```
./gradlew installDebug
```

- [ ] **Step 2: Validate — edit existing album with existing copies**

Open an album that already has copies entered.  
Tap Edit (pencil icon).  
**Verify:** Copy selector shows "Copy 1 of N" (where N matches the actual number of copies in the album). Tapping it lists all copies by number. Switching copies shows that copy's grade/country/listened values correctly — not blank, not wrong values. Tap Save without changing anything. **Verify:** all copies still exist in the album detail view with their original values unchanged.

- [ ] **Step 3: Validate — add a copy in edit mode**

Open Edit on any album. Tap "Add Copy". **Verify:** selector jumps to the new blank copy. Fill in country. Tap Save. **Verify:** album detail shows one more copy than before, with the correct values.

- [ ] **Step 4: Validate — remove a copy in edit mode (existing DB record)**

Open Edit on an album with 2+ copies. Select a copy. Tap "Remove Copy". **Verify:** confirmation dialog appears with the correct copy number. Tap Cancel — **verify** nothing changes. Tap Remove Copy again, then Delete. Tap Save. **Verify:** that copy is gone from album detail, all other copies still present and unchanged.

- [ ] **Step 5: Validate — add new album with multiple copies**

Tap the add-album FAB. Fill in artist, album name, country on Copy 1. Tap "Add Copy". Fill in a different country on Copy 2. Tap "Add Album". **Verify:** new album appears in the list with 2 copies in its detail view.

- [ ] **Step 6: Validate — remove an unsaved copy (no dialog)**

Start adding a new album. Tap "Add Copy" twice (3 total). Select Copy 3. Tap "Remove Copy". **Verify:** no dialog appears, Copy 3 is immediately removed, selector shows "Copy 2 of 2".

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModel.kt
git add app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumScreen.kt
git commit -m "feat: add multi-copy management to AddEditAlbum screen"
```

- [ ] **Step 8: Push**

```bash
git push
```
