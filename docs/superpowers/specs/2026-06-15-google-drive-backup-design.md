# Google Drive Backup Design

**Date:** 2026-06-15
**Status:** Approved

## Overview

Add backup and restore functionality to the RecordsApp, using Google Drive as the storage backend. The user can manually trigger a backup (albums + cover art) and restore from a previous backup with a choice of Replace or Merge.

No backend server is used — the Android app communicates directly with the Google Drive REST API v3 via OkHttp (already in the project). Authentication uses `play-services-auth` (Google Sign-In).

---

## Google Cloud / OAuth Setup

Before writing app code, the following configuration must be completed:

1. In the **Google Cloud Console**: enable the **Google Drive API** for the project
2. Create an **OAuth 2.0 Client ID** of type *Android*:
   - Package name: `com.recordsapp`
   - SHA-1 fingerprint of the debug (and release) signing certificate
3. Create an **OAuth 2.0 Client ID** of type *Web application* — required by `play-services-auth` for the token exchange flow
4. The requested Drive scope is `DriveScopes.DRIVE_FILE` — grants access only to files created by the app (least-privilege)

In `libs.versions.toml` + `build.gradle.kts`, add:
- `play-services-auth` (Google Sign-In)

No other new dependencies are needed; OkHttp handles all HTTP calls.

---

## Architecture

Four new components added to the existing layered structure:

### `data/drive/DriveAuthManager`
Wraps `GoogleSignInClient`. Responsible for:
- Building the sign-in intent with `DriveScopes.DRIVE_FILE`
- Retrieving the current signed-in account via `GoogleSignIn.getLastSignedInAccount()`
- Returning an access token for use in Drive API calls
- Sign-out

### `data/drive/DriveBackupRepository`
All Google Drive REST API v3 calls via OkHttp:
- Find or create the `RecordsApp` folder (app-private)
- Upload a file to the folder (delete existing same-named file first, then upload)
- Download a file by name from the folder
- List files in the folder

### `data/drive/BackupSerializer`
Pure Kotlin (no Android dependencies). Converts:
- `List<AlbumWithCopies>` → JSON string (for backup)
- JSON string → `List<AlbumWithCopies>` (for restore)

### `ui/screens/backup/BackupScreen` + `BackupViewModel`
New Compose screen and `@HiltViewModel`. Wired into the nav graph. Accessed via a settings icon on the `MyRecordsScreen` top bar.

A new Hilt module (or extension of `DatabaseModule`) provides `DriveBackupRepository` as a `@Singleton`.

---

## Backup Flow

Triggered by tapping **"Backup to Google Drive"**:

1. Check sign-in status; if not signed in, launch Google Sign-In intent
2. Load all `AlbumWithCopies` from `AlbumRepository`
3. `BackupSerializer` serializes to JSON string
4. `DriveBackupRepository` finds or creates the `RecordsApp` Drive folder
5. Upload `backup.json` (delete existing file first if present)
6. For each album with a non-null `coverImagePath`:
   - Upload the image using the base filename (e.g., `cover_abc123.jpg`)
   - Delete existing file with the same name first
7. Save the backup timestamp to `SharedPreferences`
8. Show success snackbar or error snackbar

The operation runs on `Dispatchers.IO`. Progress is reported via a status string exposed on the `StateFlow` (e.g., "Uploading cover art 3 / 12…").

---

## Restore Flow

Triggered by tapping **"Restore from Google Drive"**:

1. Check sign-in status; if not signed in, launch Google Sign-In intent
2. Download `backup.json` from the `RecordsApp` Drive folder
3. Show a confirmation dialog with two radio options:
   - **Replace** — deletes all current records, inserts everything from backup
   - **Merge** — inserts only albums not already present locally (matched by `artist` + `albumName`)
4. User confirms; operation proceeds:
   - **Replace path**: `AlbumRepository.deleteAll()`, then insert all albums + copies
   - **Merge path**: for each album in backup, skip if `artist` + `albumName` already exists locally (case-sensitive exact match), otherwise insert with its copies
5. For each album being inserted with a `coverImageFile`:
   - Download the image from the Drive folder
   - Save via existing `ImageStorage` mechanism
   - Store the resulting local path in `AlbumEntity.coverImagePath`
6. Show success snackbar ("Restored 24 albums") or error snackbar

---

## UI

**Entry point:** `Icons.Default.Settings` icon in the `MyRecordsScreen` top bar → navigates to `BackupScreen`.

**`BackupScreen` layout:**

1. **Google Account row**
   - Signed in: account email + initial avatar chip + "Sign out" text button
   - Not signed in: "Connect Google Account" button

2. **Backup section**
   - "Backup to Google Drive" — filled `Button`
   - Subtitle: "Last backup: June 15, 2026 at 10:32" (from SharedPreferences) or "Never backed up"
   - During operation: `LinearProgressIndicator` + status string

3. **Restore section**
   - "Restore from Google Drive" — outlined `OutlinedButton` (visually secondary)
   - During operation: same progress indicator pattern

4. **Restore confirmation dialog**
   - Title: "Restore backup?"
   - Two `RadioButton` options: Replace / Merge (with short descriptions)
   - Confirm + Cancel buttons

Both buttons are disabled while any operation is in progress. Errors surface as a `Snackbar`.

---

## Backup JSON Format

File name: `backup.json`  
Location: `RecordsApp/` folder in user's Google Drive (app-private)

```json
{
  "version": 1,
  "exportedAt": "2026-06-15T10:32:00Z",
  "albums": [
    {
      "artist": "Pink Floyd",
      "albumName": "The Wall",
      "numRecords": 2,
      "year": 1979,
      "comment": "Original UK pressing",
      "coverImageFile": "cover_abc123.jpg",
      "copies": [
        {
          "gradeSide1": "Very Good Plus",
          "gradeSide2": "Very Good",
          "country": "UK",
          "listened": true
        }
      ]
    }
  ]
}
```

**Notes:**
- `coverImageFile`: base filename only (the file sits alongside `backup.json` in the same Drive folder); `null` if no cover art
- `gradeSide1`, `gradeSide2`, `country`: stored as `displayName` strings, matching the existing Room storage format — no conversion needed on import
- `version`: reserved for future format migrations; current version is `1`
- `year` and `comment` may be `null` if not set on the album

---

## Drive Folder Structure

```
RecordsApp/           ← app-private folder, DRIVE_FILE scope
  backup.json
  cover_abc123.jpg
  cover_def456.jpg
  ...
```

---

## Error Handling

- **No internet**: caught as an `IOException`; show "Backup failed: no internet connection"
- **Not signed in**: prompt sign-in before proceeding
- **Drive folder not found on restore**: show "No backup found on Google Drive"
- **Partial image failure**: if one cover image fails to upload/download, log the error and continue — the album record is never blocked by a missing image
- **Replace path interrupted**: wrap the delete + insert in a Room transaction so the DB is never left in a partially-cleared state

---

## Out of Scope

- Automatic scheduled backups
- Multiple backup slots / history
- Sharing backups between users
- Backup of app settings or preferences