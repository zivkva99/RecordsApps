# Record Recognition via AI Vision — Design Spec

**Date:** 2026-05-19
**Status:** Approved

## Overview

When the user takes a photo of a vinyl record cover in the add-album flow, the app automatically calls the Gemini API to identify the record and pre-fills the form fields. A confirmation bottom sheet is always shown so the user can accept, reject, or retake the photo.

---

## Architecture

### New Components

**`GeminiRecognitionService`** (`data/remote/GeminiRecognitionService.kt`, `@Singleton`)
- Accepts an image file path, base64-encodes it, and calls the Gemini REST API
- Returns a `RecognitionResult`
- Injected into `AddEditAlbumViewModel` via Hilt

**`RecognitionResult`** (data class)
```kotlin
data class RecognitionResult(
    val artistName: String,
    val albumName: String,
    val year: String,
    val numRecords: String,
    val confidence: Confidence
)

enum class Confidence { HIGH, LOW }
```

**`RecognitionState`** (sealed class, lives in `AddEditAlbumViewModel.kt`)
```kotlin
sealed class RecognitionState {
    object Idle : RecognitionState()
    object Loading : RecognitionState()
    data class Result(val result: RecognitionResult) : RecognitionState()
    data class Error(val message: String) : RecognitionState()
}
```

**`RecordRecognitionBottomSheet`** (`ui/components/RecordRecognitionBottomSheet.kt`)
- A `ModalBottomSheet` composable displayed over `AddEditAlbumScreen`
- Non-dismissible — user must tap Accept, Reject, or Retake

### Existing Components Modified

- **`AddEditAlbumViewModel`** — gains `recognitionState: StateFlow<RecognitionState>` and calls `recognizeRecord(uri)` from `onCoverImageChanged`
- **`AddEditAlbumScreen`** — shows the bottom sheet when state is `Loading` or `Result`
- **`DatabaseModule` / new `NetworkModule`** — provides `GeminiRecognitionService` via Hilt
- **`local.properties`** — API key stored here (`gemini_api_key=...`), exposed via `BuildConfig`

---

## Data Flow

1. User takes photo via existing camera in `CoverImagePicker`
2. `onCoverImageChanged(uri)` fires — saves URI for cover image (unchanged)
3. ViewModel calls `recognizeRecord(uri)` → `recognitionState` = `Loading`
4. Bottom sheet appears with a loading spinner
5. `GeminiRecognitionService` returns → `recognitionState` = `Result(...)`
6. Bottom sheet shows confirmation card with found details and confidence level

**Accept** → form fields fill from result; `recognitionState` = `Idle`
**Reject** → `recognitionState` = `Idle`; form stays empty
**Retake** → `recognitionState` = `Idle`; ViewModel emits a `retakeRequested` `SharedFlow<Unit>` event that `AddEditAlbumScreen` observes to re-launch the camera picker

**On error** → `recognitionState` = `Error(message)`; bottom sheet shows the error message with Reject and Retake buttons only.

**Fields auto-filled:** artist name, album name, year, number of records.
**Not filled:** grade, country, comment (require human judgment about physical condition/pressing).

---

## Bottom Sheet UI

**Loading state:** `CircularProgressIndicator` + "Identifying record…" label.

**Result state:**
```
┌─────────────────────────────────────┐
│  Record Identified                  │
│  ⚠ Low confidence — please verify  │  ← only if confidence == LOW
│                                     │
│  Artist   Pink Floyd                │
│  Album    The Wall                  │
│  Year     1979                      │
│  Records  2                         │
│                                     │
│  [Accept]  [Reject]  [Retake Photo] │
└─────────────────────────────────────┘
```

- High confidence: no warning shown, title "Record Identified"
- Low confidence: yellow warning row displayed below title
- All three buttons always present regardless of confidence

**Error state:** Shows the error message with Reject and Retake buttons only.

---

## Gemini API Integration

**Endpoint:**
```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key={API_KEY}
```

**Prompt:**
```
You are identifying a vinyl record from its cover photo.
Return ONLY a JSON object with these fields:
{
  "artistName": "...",
  "albumName": "...",
  "year": "...",
  "numRecords": "...",
  "confidence": "high" or "low"
}
Use "low" confidence if the cover is unclear, partially visible, or you are not certain.
If a field cannot be determined, use an empty string.
```

**Response parsing:** Extract `candidates[0].content.parts[0].text`, strip markdown code fences if present, parse with `org.json.JSONObject` (built into Android — no new dependency).

**HTTP client:** OkHttp (already a transitive dependency).

---

## Error Handling

| Situation | Message shown |
|---|---|
| Network unavailable | "No internet connection. Retake or fill manually." |
| Unparseable JSON response | "Couldn't read the result." |
| API key missing / 403 | "Recognition unavailable." |
| Timeout (>15s) | "Recognition timed out." |

All error states show the bottom sheet with Reject and Retake buttons.

---

## API Key Setup

1. Add `gemini_api_key=YOUR_KEY` to `local.properties` (git-ignored)
2. Expose via `BuildConfig` in `app/build.gradle.kts`:
   ```kotlin
   buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties["gemini_api_key"]}\"")
   ```
3. `GeminiRecognitionService` reads `BuildConfig.GEMINI_API_KEY`
