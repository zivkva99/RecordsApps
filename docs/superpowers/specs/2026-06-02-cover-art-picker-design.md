# Cover Art Picker Design

**Date:** 2026-06-02  
**Status:** Approved

## Problem

The recognition bottom sheet fetches only the first iTunes result for cover art. When an artist has multiple albums with the same name, the wrong cover art is selected automatically. The user also has no way to use the photo they took instead of the iTunes art.

## Goal

Show all available iTunes cover art options as selectable thumbnails in the recognition bottom sheet, including the user's own camera photo, so the user can pick the right one before accepting.

## Changes

### ItunesCoverArtService

Add `fetchUrls(artist: String, album: String): List<String>` — same iTunes search query as today (`limit=5`), but parse all returned results instead of just index 0. Each URL is upscaled from `100x100bb` to `600x600bb`. Returns an empty list on failure or no results.

Keep `fetchUrl`, `fetchAndSave`, `fetchUrlAndSave` unchanged (used elsewhere / for backward compat).

### RecognitionState.Result

Replace `coverArtUrl: String?` with `coverArtUrls: List<String>`.

In `AddEditAlbumViewModel.recognizeRecord()`, after recognition succeeds, call `fetchUrls()` instead of `fetchUrl()` and store the full list.

### Bottom Sheet — Thumbnail Row

In the `RecognitionState.Result` case, add a horizontally-scrollable `LazyRow` of square thumbnails (80dp × 80dp, 8dp corner radius) between the album detail fields and the action buttons.

**Thumbnail order:**
1. The camera photo URI (`state.coverImageUri`) — always first, if it exists
2. Each URL in `coverArtUrls`, in iTunes result order

**Selection:**
- Selection state is local `remember { mutableStateOf(...) }` in the composable, typed as `String?` where `null` = camera photo selected, non-null = the selected iTunes URL.
- Default: first entry in `coverArtUrls` if non-empty, otherwise `null` (camera photo).
- Selected thumbnail shows a 2dp `MaterialTheme.colorScheme.primary` border. Unselected thumbnails have no border.
- Tapping a thumbnail updates local selection state.

**Visibility:** If `coverArtUrls` is empty AND no camera URI exists, the thumbnail row is hidden entirely.

### onAccept signature

Change from `onAccept: () -> Unit` to `onAccept: (selectedCoverUrl: String?) -> Unit`.

- `null` → user selected the camera photo
- non-null string → user selected an iTunes URL

The bottom sheet passes the current local selection when the Accept button is tapped.

### AddEditAlbumViewModel.acceptRecognition

Change signature to `acceptRecognition(selectedCoverUrl: String?)`.

Behavior:
- Always apply text fields from the recognition result (artist, album, year, numRecords) as today.
- If `selectedCoverUrl == null`: keep `coverImageUri` as-is — the camera photo remains the cover.
- If `selectedCoverUrl != null`: call `coverArtService.fetchUrlAndSave(selectedCoverUrl)`, set `existingCoverPath = path`, clear `coverImageUri = null`.
- Set `recognitionState = RecognitionState.Idle`.

### AddEditAlbumScreen

Update the `onAccept` lambda passed to `RecordRecognitionBottomSheet` from `viewModel::acceptRecognition` (zero-arg) to `{ url -> viewModel.acceptRecognition(url) }`.

## Files Changed

| File | Change |
|---|---|
| `data/remote/ItunesCoverArtService.kt` | Add `fetchUrls()` returning `List<String>` |
| `ui/screens/addeditalbum/AddEditAlbumViewModel.kt` | `RecognitionState.Result` uses `coverArtUrls`; `recognizeRecord` calls `fetchUrls`; `acceptRecognition(selectedCoverUrl: String?)` |
| `ui/components/RecordRecognitionBottomSheet.kt` | Add thumbnail `LazyRow`; `onAccept` becomes `(String?) -> Unit` |
| `ui/screens/addeditalbum/AddEditAlbumScreen.kt` | Update `onAccept` lambda |

## Out of Scope

- Persisting the user's cover art choice before Accept is tapped.
- Allowing more than 5 iTunes results (limit=5 is sufficient).
- Changing the Error or Loading states of the bottom sheet.
