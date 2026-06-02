# Multi-Copy Edit in AddEditAlbum Screen

**Date:** 2026-06-02  
**Status:** Approved

## Problem

The AddEditAlbum screen handles only a single copy. Adding additional copies requires navigating to a separate AddCopy screen from AlbumDetail. There is no way to manage multiple copies while adding or editing an album in one flow.

## Goal

Allow users to add, switch between, and remove copies directly within the AddEditAlbum screen — both when creating a new album and when editing an existing one.

## UI Design

The "Copy Details" section is replaced with a multi-copy section:

1. **Header row**: "Copy Details" label on the left. A `DropdownMenu`-style selector on the right showing the currently selected copy ("Copy 1", "Copy 2", …). Tapping it opens a menu listing all copies; selecting one switches the fields below.
2. **Copy fields**: Grade Side 1, Grade Side 2, Country, Listened — same as today, but scoped to the selected copy.
3. **Action row** below the fields:
   - "Add Copy" text button on the left — appends a blank copy and selects it.
   - "Remove Copy" text button (in error/red color) on the right — visible only when there are 2+ copies. Tapping shows a confirmation dialog before marking the copy for deletion.

### Confirmation dialog

> **Delete Copy #N?**  
> This cannot be undone.  
> [Cancel] [Delete]

Only shown when removing an existing DB copy (one with an `id`). New unsaved copies are removed immediately without a dialog.

## State

### New data class

```kotlin
data class CopyFormState(
    val id: Long? = null,        // null = new (not yet in DB)
    val gradeSide1: Grade? = null,
    val gradeSide2: Grade? = null,
    val country: Country? = null,
    val listened: Boolean = false
)
```

### AddEditAlbumState changes

Remove flat fields `gradeSide1`, `gradeSide2`, `country`, `listened`, `copyId`.  
Add:

```kotlin
val copies: List<CopyFormState> = listOf(CopyFormState())
val selectedCopyIndex: Int = 0
val removedCopyIds: Set<Long> = emptySet()
val showRemoveCopyDialog: Boolean = false
```

## ViewModel

### Loading (edit mode)

Load **all** copies for the album. Each becomes a `CopyFormState` with its real DB `id` and field values. `selectedCopyIndex` starts at 0.

### New functions

| Function | Behavior |
|---|---|
| `selectCopy(index)` | Sets `selectedCopyIndex` |
| `addCopy()` | Appends blank `CopyFormState(id = null)`, sets `selectedCopyIndex` to the new last index |
| `requestRemoveCopy()` | If selected copy has an `id`: sets `showRemoveCopyDialog = true`. If no `id` (new unsaved): removes immediately from list, moves selection to previous index |
| `confirmRemoveCopy()` | Moves selected copy's `id` into `removedCopyIds`, removes from `copies` list, moves selection to previous index, clears dialog flag |
| `dismissRemoveCopyDialog()` | Clears `showRemoveCopyDialog` |
| `onGradeSide1Changed(grade)` | Mutates `copies[selectedCopyIndex]` |
| `onGradeSide2Changed(grade)` | Mutates `copies[selectedCopyIndex]` |
| `onCountryChanged(country)` | Mutates `copies[selectedCopyIndex]` |
| `onListenedChanged(value)` | Mutates `copies[selectedCopyIndex]` |

### Save logic

**Add mode:**
1. Insert album via `insertAlbumWithCopy(album, copies[0])`.
2. For each remaining copy in `copies` (index 1+): `insertCopy(copy.copy(albumId = newAlbumId))`.

**Edit mode:**
1. `updateAlbum(album)`.
2. For each copy in `copies` where `id != null`: `updateCopy(...)`.
3. For each copy in `copies` where `id == null`: `insertCopy(copy.copy(albumId = albumId))`.
4. For each id in `removedCopyIds`: `deleteCopy(...)`.

> **Safety invariant:** The only copies deleted are those whose ids appear in `removedCopyIds`. This set is populated exclusively by explicit user action (confirming the Remove Copy dialog). Missing-from-list copies are never implicitly deleted.

### Save button validation

- `artistName` and `albumName` must be non-blank.
- Every copy must have a non-null `country`.
- For each copy where `listened == true`, both `gradeSide1` and `gradeSide2` must be non-null.

## Files Changed

| File | Change |
|---|---|
| `ui/screens/addeditalbum/AddEditAlbumViewModel.kt` | Replace flat copy fields with `CopyFormState` list; add copy management functions; update save logic |
| `ui/screens/addeditalbum/AddEditAlbumScreen.kt` | Replace copy fields section with copy selector dropdown, scoped fields, and Add/Remove buttons; add confirmation dialog |

No changes needed to `AlbumRepository`, `CopyEntity`, DAOs, navigation, or the AddCopy screen.

## Out of Scope

- Editing copies from the AlbumDetail screen (unchanged).
- The standalone AddCopy screen (unchanged; still accessible from AlbumDetail toolbar).
