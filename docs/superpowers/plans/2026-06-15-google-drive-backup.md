# Google Drive Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Backup/Restore screen that saves all albums, copies, and cover art to a single app-private folder on Google Drive, and can restore them with Replace or Merge semantics.

**Architecture:** `DriveAuthManager` handles Google Sign-In and OAuth token retrieval via `play-services-auth`. `DriveBackupRepository` makes Drive REST v3 calls via OkHttp (already in project). `BackupSerializer` converts Room data ↔ JSON using the Android-bundled `org.json`. `BackupViewModel` orchestrates the three, and `BackupScreen` is a new Compose screen reachable via a settings icon on the album list.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt/KSP, Room, OkHttp 4, `play-services-auth:21.2.0`, `org.json` (Android platform — no new dependency)

---

## File Map

| Action | Path |
|--------|------|
| Create | `app/src/main/java/com/recordsapp/data/drive/BackupModels.kt` |
| Create | `app/src/main/java/com/recordsapp/data/drive/BackupSerializer.kt` |
| Create | `app/src/main/java/com/recordsapp/data/drive/DriveAuthManager.kt` |
| Create | `app/src/main/java/com/recordsapp/data/drive/DriveBackupRepository.kt` |
| Create | `app/src/main/java/com/recordsapp/di/BackupModule.kt` |
| Create | `app/src/main/java/com/recordsapp/ui/screens/backup/BackupViewModel.kt` |
| Create | `app/src/main/java/com/recordsapp/ui/screens/backup/BackupScreen.kt` |
| Create | `app/src/test/java/com/recordsapp/data/drive/BackupSerializerTest.kt` |
| Modify | `gradle/libs.versions.toml` |
| Modify | `app/build.gradle.kts` |
| Modify | `app/src/main/java/com/recordsapp/data/local/dao/AlbumDao.kt` |
| Modify | `app/src/main/java/com/recordsapp/data/repository/AlbumRepository.kt` |
| Modify | `app/src/main/java/com/recordsapp/ui/navigation/Screen.kt` |
| Modify | `app/src/main/java/com/recordsapp/ui/navigation/NavGraph.kt` |
| Modify | `app/src/main/java/com/recordsapp/ui/screens/albumlist/AlbumListScreen.kt` |

---

## Task 1: Google Cloud Console Setup

**Manual steps (no code — do these before writing any code):**

- [ ] **Step 1: Enable the Drive API**

  Go to https://console.cloud.google.com → select or create a project → APIs & Services → Enable APIs → search "Google Drive API" → Enable.

- [ ] **Step 2: Create the Android OAuth client ID**

  APIs & Services → Credentials → Create Credentials → OAuth 2.0 Client ID → Application type: Android.
  - Package name: `com.recordsapp`
  - SHA-1 fingerprint: run `./gradlew signingReport` and copy the SHA1 from the `debug` variant.

  Save the client ID (you won't need to paste it into code — Android uses the package+SHA1 combination automatically).

- [ ] **Step 3: Configure the OAuth consent screen**

  APIs & Services → OAuth consent screen → External → fill in app name and your email. Add scope: `../auth/drive.file`. Save.

---

## Task 2: Add play-services-auth Dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version and library alias to `gradle/libs.versions.toml`**

  In the `[versions]` block, add:
  ```toml
  play-services-auth = "21.2.0"
  ```
  In the `[libraries]` block, add:
  ```toml
  play-services-auth = { group = "com.google.android.gms", name = "play-services-auth", version.ref = "play-services-auth" }
  ```

- [ ] **Step 2: Add dependency to `app/build.gradle.kts`**

  Inside the `dependencies { }` block, after the existing `implementation(libs.okhttp)` line, add:
  ```kotlin
  implementation(libs.play.services.auth)
  ```

- [ ] **Step 3: Sync and verify build**

  Run:
  ```
  ./gradlew assembleDebug
  ```
  Expected: BUILD SUCCESSFUL. If you see "unresolved reference: play", check that the alias in `libs.versions.toml` uses a hyphen (`play-services-auth`) and that Gradle has synced.

- [ ] **Step 4: Commit**

  ```bash
  git add gradle/libs.versions.toml app/build.gradle.kts
  git commit -m "feat: add play-services-auth dependency for Google Drive backup"
  ```

---

## Task 3: BackupModels Data Classes

**Files:**
- Create: `app/src/main/java/com/recordsapp/data/drive/BackupModels.kt`

- [ ] **Step 1: Create the file**

  ```kotlin
  package com.recordsapp.data.drive

  data class BackupCopy(
      val gradeSide1: String,
      val gradeSide2: String,
      val country: String,
      val listened: Boolean
  )

  data class BackupAlbum(
      val artist: String,
      val albumName: String,
      val numRecords: Int,
      val year: Int,
      val comment: String,
      val coverImageFile: String?,
      val copies: List<BackupCopy>
  )
  ```

- [ ] **Step 2: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/data/drive/BackupModels.kt
  git commit -m "feat: add backup JSON data models"
  ```

---

## Task 4: BackupSerializer (TDD)

**Files:**
- Create: `app/src/test/java/com/recordsapp/data/drive/BackupSerializerTest.kt`
- Create: `app/src/main/java/com/recordsapp/data/drive/BackupSerializer.kt`

- [ ] **Step 1: Write the failing tests**

  Create `app/src/test/java/com/recordsapp/data/drive/BackupSerializerTest.kt`:

  ```kotlin
  package com.recordsapp.data.drive

  import com.recordsapp.data.local.entity.AlbumEntity
  import com.recordsapp.data.local.entity.CopyEntity
  import com.recordsapp.data.local.relation.AlbumWithCopies
  import org.json.JSONObject
  import org.junit.Assert.*
  import org.junit.Test

  class BackupSerializerTest {

      private val serializer = BackupSerializer()

      private fun awc(
          artistName: String = "Pink Floyd",
          albumName: String = "The Wall",
          numRecords: Int = 2,
          year: Int = 1979,
          comment: String = "Original pressing",
          coverImagePath: String? = "/data/user/0/com.recordsapp/files/cover_abc.jpg"
      ) = AlbumWithCopies(
          album = AlbumEntity(
              id = 1,
              artistName = artistName,
              albumName = albumName,
              numRecords = numRecords,
              year = year,
              coverImagePath = coverImagePath,
              comment = comment
          ),
          copies = listOf(
              CopyEntity(
                  id = 1, albumId = 1,
                  gradeSide1 = "Very Good Plus", gradeSide2 = "Very Good",
                  country = "UK", listened = true
              )
          )
      )

      @Test
      fun `serialize produces correct album fields`() {
          val json = serializer.serialize(listOf(awc()))
          val album = JSONObject(json).getJSONArray("albums").getJSONObject(0)
          assertEquals("Pink Floyd", album.getString("artist"))
          assertEquals("The Wall", album.getString("albumName"))
          assertEquals(2, album.getInt("numRecords"))
          assertEquals(1979, album.getInt("year"))
          assertEquals("Original pressing", album.getString("comment"))
          assertEquals("cover_abc.jpg", album.getString("coverImageFile"))
      }

      @Test
      fun `serialize produces correct copy fields`() {
          val json = serializer.serialize(listOf(awc()))
          val copy = JSONObject(json).getJSONArray("albums").getJSONObject(0)
              .getJSONArray("copies").getJSONObject(0)
          assertEquals("Very Good Plus", copy.getString("gradeSide1"))
          assertEquals("Very Good", copy.getString("gradeSide2"))
          assertEquals("UK", copy.getString("country"))
          assertTrue(copy.getBoolean("listened"))
      }

      @Test
      fun `serialize sets coverImageFile null when no cover path`() {
          val json = serializer.serialize(listOf(awc(coverImagePath = null)))
          val album = JSONObject(json).getJSONArray("albums").getJSONObject(0)
          assertTrue(album.isNull("coverImageFile"))
      }

      @Test
      fun `serialize includes version 1 and exportedAt`() {
          val json = serializer.serialize(emptyList())
          val root = JSONObject(json)
          assertEquals(1, root.getInt("version"))
          assertTrue(root.getString("exportedAt").isNotEmpty())
      }

      @Test
      fun `deserialize returns correct number of albums`() {
          val json = serializer.serialize(listOf(awc(), awc(artistName = "Led Zeppelin", albumName = "IV")))
          assertEquals(2, serializer.deserialize(json).size)
      }

      @Test
      fun `round-trip preserves all album and copy fields`() {
          val result = serializer.deserialize(serializer.serialize(listOf(awc()))).single()
          assertEquals("Pink Floyd", result.artist)
          assertEquals("The Wall", result.albumName)
          assertEquals(2, result.numRecords)
          assertEquals(1979, result.year)
          assertEquals("Original pressing", result.comment)
          assertEquals("cover_abc.jpg", result.coverImageFile)
          assertEquals(1, result.copies.size)
          assertEquals("Very Good Plus", result.copies[0].gradeSide1)
          assertEquals("UK", result.copies[0].country)
          assertTrue(result.copies[0].listened)
      }

      @Test
      fun `round-trip preserves null coverImageFile`() {
          val result = serializer.deserialize(serializer.serialize(listOf(awc(coverImagePath = null)))).single()
          assertNull(result.coverImageFile)
      }

      @Test
      fun `deserialize handles empty albums array`() {
          val json = """{"version":1,"exportedAt":"2026-06-15T10:00:00Z","albums":[]}"""
          assertTrue(serializer.deserialize(json).isEmpty())
      }
  }
  ```

- [ ] **Step 2: Run tests to confirm they fail**

  ```
  ./gradlew test --tests "com.recordsapp.data.drive.BackupSerializerTest"
  ```
  Expected: compilation error — `BackupSerializer` does not exist yet.

- [ ] **Step 3: Implement BackupSerializer**

  Create `app/src/main/java/com/recordsapp/data/drive/BackupSerializer.kt`:

  ```kotlin
  package com.recordsapp.data.drive

  import com.recordsapp.data.local.relation.AlbumWithCopies
  import org.json.JSONArray
  import org.json.JSONObject
  import java.io.File
  import java.time.Instant
  import javax.inject.Inject
  import javax.inject.Singleton

  @Singleton
  class BackupSerializer @Inject constructor() {

      fun serialize(albums: List<AlbumWithCopies>): String {
          val root = JSONObject()
          root.put("version", 1)
          root.put("exportedAt", Instant.now().toString())
          val albumsArray = JSONArray()
          albums.forEach { awc ->
              val albumObj = JSONObject()
              albumObj.put("artist", awc.album.artistName)
              albumObj.put("albumName", awc.album.albumName)
              albumObj.put("numRecords", awc.album.numRecords)
              albumObj.put("year", awc.album.year)
              albumObj.put("comment", awc.album.comment)
              albumObj.put("coverImageFile", awc.album.coverImagePath?.let { File(it).name })
              val copiesArray = JSONArray()
              awc.copies.forEach { copy ->
                  val copyObj = JSONObject()
                  copyObj.put("gradeSide1", copy.gradeSide1)
                  copyObj.put("gradeSide2", copy.gradeSide2)
                  copyObj.put("country", copy.country)
                  copyObj.put("listened", copy.listened)
                  copiesArray.put(copyObj)
              }
              albumObj.put("copies", copiesArray)
              albumsArray.put(albumObj)
          }
          root.put("albums", albumsArray)
          return root.toString()
      }

      fun deserialize(json: String): List<BackupAlbum> {
          val albumsArray = JSONObject(json).getJSONArray("albums")
          return (0 until albumsArray.length()).map { i ->
              val obj = albumsArray.getJSONObject(i)
              val copiesArray = obj.getJSONArray("copies")
              BackupAlbum(
                  artist = obj.getString("artist"),
                  albumName = obj.getString("albumName"),
                  numRecords = obj.getInt("numRecords"),
                  year = obj.getInt("year"),
                  comment = obj.optString("comment", ""),
                  coverImageFile = if (obj.has("coverImageFile") && !obj.isNull("coverImageFile"))
                      obj.getString("coverImageFile") else null,
                  copies = (0 until copiesArray.length()).map { j ->
                      val c = copiesArray.getJSONObject(j)
                      BackupCopy(
                          gradeSide1 = c.getString("gradeSide1"),
                          gradeSide2 = c.getString("gradeSide2"),
                          country = c.getString("country"),
                          listened = c.getBoolean("listened")
                      )
                  }
              )
          }
      }
  }
  ```

- [ ] **Step 4: Run tests to confirm they pass**

  ```
  ./gradlew test --tests "com.recordsapp.data.drive.BackupSerializerTest"
  ```
  Expected: 8 tests, all PASS.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/data/drive/BackupSerializer.kt \
          app/src/test/java/com/recordsapp/data/drive/BackupSerializerTest.kt
  git commit -m "feat: add BackupSerializer with JSON round-trip tests"
  ```

---

## Task 5: Extend AlbumDao and AlbumRepository

**Files:**
- Modify: `app/src/main/java/com/recordsapp/data/local/dao/AlbumDao.kt`
- Modify: `app/src/main/java/com/recordsapp/data/repository/AlbumRepository.kt`

- [ ] **Step 1: Add two queries to AlbumDao**

  In `AlbumDao.kt`, add these two methods after the existing `deleteAlbum`:

  ```kotlin
  @Query("DELETE FROM albums")
  suspend fun deleteAllAlbums()

  @Query("SELECT COUNT(*) FROM albums WHERE artistName = :artist AND albumName = :album")
  suspend fun countByArtistAndAlbum(artist: String, album: String): Int
  ```

- [ ] **Step 2: Inject RecordsDatabase and add four methods to AlbumRepository**

  Update the constructor to also inject `RecordsDatabase`:
  ```kotlin
  @Singleton
  class AlbumRepository @Inject constructor(
      private val albumDao: AlbumDao,
      private val copyDao: CopyDao,
      private val database: RecordsDatabase
  )
  ```

  Add these four methods after the existing `deleteCopy`:

  ```kotlin
  suspend fun getAllAlbumsWithCopiesOnce(): List<AlbumWithCopies> =
      albumDao.getAllAlbumsWithCopies().first()

  suspend fun deleteAll() = albumDao.deleteAllAlbums()

  suspend fun albumExists(artist: String, albumName: String): Boolean =
      albumDao.countByArtistAndAlbum(artist, albumName) > 0

  suspend fun <T> withTransaction(block: suspend () -> T): T = database.withTransaction(block)
  ```

  Add these imports at the top of `AlbumRepository.kt`:
  ```kotlin
  import androidx.room.withTransaction
  import com.recordsapp.data.local.RecordsDatabase
  import kotlinx.coroutines.flow.first
  ```

- [ ] **Step 3: Verify build**

  ```
  ./gradlew assembleDebug
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/data/local/dao/AlbumDao.kt \
          app/src/main/java/com/recordsapp/data/repository/AlbumRepository.kt
  git commit -m "feat: add deleteAll, albumExists, and getAllAlbumsWithCopiesOnce to repository"
  ```

---

## Task 6: DriveAuthManager

**Files:**
- Create: `app/src/main/java/com/recordsapp/data/drive/DriveAuthManager.kt`

- [ ] **Step 1: Create DriveAuthManager**

  ```kotlin
  package com.recordsapp.data.drive

  import android.content.Context
  import android.content.Intent
  import com.google.android.gms.auth.GoogleAuthUtil
  import com.google.android.gms.auth.api.signin.GoogleSignIn
  import com.google.android.gms.auth.api.signin.GoogleSignInAccount
  import com.google.android.gms.auth.api.signin.GoogleSignInOptions
  import com.google.android.gms.common.api.ApiException
  import com.google.android.gms.common.api.Scope
  import com.google.android.gms.tasks.Tasks
  import dagger.hilt.android.qualifiers.ApplicationContext
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import javax.inject.Inject
  import javax.inject.Singleton

  private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

  @Singleton
  class DriveAuthManager @Inject constructor(
      @ApplicationContext private val context: Context
  ) {
      private val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
          .requestEmail()
          .requestScopes(Scope(DRIVE_FILE_SCOPE))
          .build()

      private val client = GoogleSignIn.getClient(context, options)

      fun signInIntent(): Intent = client.signInIntent

      fun currentAccountEmail(): String? =
          GoogleSignIn.getLastSignedInAccount(context)?.email

      fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
          return try {
              GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
          } catch (e: ApiException) {
              null
          }
      }

      suspend fun accessToken(): String = withContext(Dispatchers.IO) {
          val account = GoogleSignIn.getLastSignedInAccount(context)
              ?: error("Not signed in")
          GoogleAuthUtil.getToken(context, account.account!!, "oauth2:$DRIVE_FILE_SCOPE")
      }

      suspend fun signOut() = withContext(Dispatchers.IO) {
          Tasks.await(client.signOut())
      }
  }
  ```

- [ ] **Step 2: Verify build**

  ```
  ./gradlew assembleDebug
  ```
  Expected: BUILD SUCCESSFUL. If you see unresolved imports for `GoogleAuthUtil`, confirm that `play-services-auth` is in `build.gradle.kts` (Task 2).

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/data/drive/DriveAuthManager.kt
  git commit -m "feat: add DriveAuthManager for Google Sign-In and token retrieval"
  ```

---

## Task 7: DriveBackupRepository

**Files:**
- Create: `app/src/main/java/com/recordsapp/data/drive/DriveBackupRepository.kt`

- [ ] **Step 1: Create DriveBackupRepository**

  ```kotlin
  package com.recordsapp.data.drive

  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import okhttp3.Headers
  import okhttp3.MediaType.Companion.toMediaType
  import okhttp3.MultipartBody
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import okhttp3.RequestBody.Companion.toRequestBody
  import org.json.JSONObject
  import java.net.URLEncoder
  import javax.inject.Inject
  import javax.inject.Singleton

  data class RestoreResult(
      val json: String,
      val coverImages: Map<String, ByteArray>
  )

  @Singleton
  class DriveBackupRepository @Inject constructor() {

      private val client = OkHttpClient()

      companion object {
          private const val BASE = "https://www.googleapis.com/drive/v3"
          private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
          private const val FOLDER_MIME = "application/vnd.google-apps.folder"
          private const val FOLDER_NAME = "RecordsApp"
      }

      private suspend fun findOrCreateFolder(token: String): String = withContext(Dispatchers.IO) {
          val query = "name='$FOLDER_NAME' and mimeType='$FOLDER_MIME' and 'root' in parents and trashed=false"
          val url = "$BASE/files?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id)"
          val searchResponse = client.newCall(
              Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
          ).execute()
          val files = JSONObject(searchResponse.body!!.string()).getJSONArray("files")
          if (files.length() > 0) return@withContext files.getJSONObject(0).getString("id")

          val metadata = """{"name":"$FOLDER_NAME","mimeType":"$FOLDER_MIME","parents":["root"]}"""
          val createResponse = client.newCall(
              Request.Builder()
                  .url("$BASE/files")
                  .addHeader("Authorization", "Bearer $token")
                  .post(metadata.toRequestBody("application/json".toMediaType()))
                  .build()
          ).execute()
          JSONObject(createResponse.body!!.string()).getString("id")
      }

      private suspend fun listFiles(token: String, folderId: String): Map<String, String> =
          withContext(Dispatchers.IO) {
              val query = "'$folderId' in parents and trashed=false"
              val url = "$BASE/files?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)"
              val response = client.newCall(
                  Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
              ).execute()
              val arr = JSONObject(response.body!!.string()).getJSONArray("files")
              (0 until arr.length()).associate { i ->
                  val obj = arr.getJSONObject(i)
                  obj.getString("name") to obj.getString("id")
              }
          }

      private suspend fun deleteFile(token: String, fileId: String) = withContext(Dispatchers.IO) {
          client.newCall(
              Request.Builder()
                  .url("$BASE/files/$fileId")
                  .addHeader("Authorization", "Bearer $token")
                  .delete()
                  .build()
          ).execute()
      }

      private suspend fun uploadFile(
          token: String,
          folderId: String,
          name: String,
          bytes: ByteArray,
          mimeType: String
      ) = withContext(Dispatchers.IO) {
          val metadata = """{"name":"$name","parents":["$folderId"]}"""
          val body = MultipartBody.Builder()
              .setType("multipart/related".toMediaType())
              .addPart(
                  Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                  metadata.toRequestBody()
              )
              .addPart(
                  Headers.headersOf("Content-Type", mimeType),
                  bytes.toRequestBody()
              )
              .build()
          client.newCall(
              Request.Builder()
                  .url("$UPLOAD_BASE/files?uploadType=multipart")
                  .addHeader("Authorization", "Bearer $token")
                  .post(body)
                  .build()
          ).execute()
      }

      private suspend fun downloadFile(token: String, fileId: String): ByteArray =
          withContext(Dispatchers.IO) {
              val response = client.newCall(
                  Request.Builder()
                      .url("$BASE/files/$fileId?alt=media")
                      .addHeader("Authorization", "Bearer $token")
                      .build()
              ).execute()
              response.body!!.bytes()
          }

      suspend fun backup(
          token: String,
          json: String,
          coverImages: Map<String, ByteArray>,
          onProgress: (String) -> Unit
      ) {
          onProgress("Connecting to Google Drive…")
          val folderId = findOrCreateFolder(token)
          val existing = listFiles(token, folderId)

          onProgress("Uploading backup data…")
          existing["backup.json"]?.let { deleteFile(token, it) }
          uploadFile(token, folderId, "backup.json", json.toByteArray(), "application/json")

          coverImages.entries.forEachIndexed { index, (name, bytes) ->
              onProgress("Uploading cover art ${index + 1} / ${coverImages.size}…")
              existing[name]?.let { deleteFile(token, it) }
              uploadFile(token, folderId, name, bytes, "image/jpeg")
          }
      }

      suspend fun restore(
          token: String,
          onProgress: (String) -> Unit
      ): RestoreResult {
          onProgress("Connecting to Google Drive…")
          val folderId = findOrCreateFolder(token)
          val files = listFiles(token, folderId)

          val jsonFileId = files["backup.json"] ?: error("No backup found on Google Drive")
          onProgress("Downloading backup data…")
          val json = downloadFile(token, jsonFileId).toString(Charsets.UTF_8)

          val imageFiles = files.filter { it.key != "backup.json" }
          val coverImages = mutableMapOf<String, ByteArray>()
          imageFiles.entries.forEachIndexed { index, (name, id) ->
              onProgress("Downloading cover art ${index + 1} / ${imageFiles.size}…")
              coverImages[name] = downloadFile(token, id)
          }

          return RestoreResult(json = json, coverImages = coverImages)
      }
  }
  ```

- [ ] **Step 2: Verify build**

  ```
  ./gradlew assembleDebug
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/data/drive/DriveBackupRepository.kt
  git commit -m "feat: add DriveBackupRepository with Drive REST API via OkHttp"
  ```

---

## Task 8: BackupModule (Hilt DI)

**Files:**
- Create: `app/src/main/java/com/recordsapp/di/BackupModule.kt`

- [ ] **Step 1: Create BackupModule**

  ```kotlin
  package com.recordsapp.di

  import android.content.Context
  import android.content.SharedPreferences
  import androidx.preference.PreferenceManager
  import dagger.Module
  import dagger.Provides
  import dagger.hilt.InstallIn
  import dagger.hilt.android.qualifiers.ApplicationContext
  import dagger.hilt.components.SingletonComponent
  import javax.inject.Singleton

  @Module
  @InstallIn(SingletonComponent::class)
  object BackupModule {

      @Provides
      @Singleton
      fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
          PreferenceManager.getDefaultSharedPreferences(context)
  }
  ```

  Note: `PreferenceManager` is from `androidx.preference` which is bundled in `androidx.core-ktx`. No new dependency needed.

- [ ] **Step 2: Verify build**

  ```
  ./gradlew assembleDebug
  ```
  Expected: BUILD SUCCESSFUL. If you see "unresolved reference: PreferenceManager", add `implementation("androidx.preference:preference-ktx:1.2.1")` to `build.gradle.kts` and re-sync.

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/di/BackupModule.kt
  git commit -m "feat: add BackupModule for SharedPreferences Hilt binding"
  ```

---

## Task 9: BackupViewModel

**Files:**
- Create: `app/src/main/java/com/recordsapp/ui/screens/backup/BackupViewModel.kt`

- [ ] **Step 1: Create BackupViewModel**

  ```kotlin
  package com.recordsapp.ui.screens.backup

  import android.content.Intent
  import android.content.SharedPreferences
  import androidx.lifecycle.ViewModel
  import androidx.lifecycle.viewModelScope
  import com.recordsapp.data.drive.BackupSerializer
  import com.recordsapp.data.drive.DriveAuthManager
  import com.recordsapp.data.drive.DriveBackupRepository
  import com.recordsapp.data.local.ImageStorage
  import com.recordsapp.data.local.entity.AlbumEntity
  import com.recordsapp.data.local.entity.CopyEntity
  import com.recordsapp.data.repository.AlbumRepository
  import dagger.hilt.android.lifecycle.HiltViewModel
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.flow.update
  import kotlinx.coroutines.launch
  import java.io.File
  import java.text.SimpleDateFormat
  import java.util.Date
  import java.util.Locale
  import javax.inject.Inject

  sealed class RestoreMode {
      object Replace : RestoreMode()
      object Merge : RestoreMode()
  }

  data class BackupState(
      val accountEmail: String? = null,
      val lastBackupTime: String? = null,
      val isOperationInProgress: Boolean = false,
      val statusMessage: String = "",
      val showRestoreDialog: Boolean = false,
      val snackbarMessage: String? = null
  )

  private const val PREF_LAST_BACKUP = "last_backup_time"

  @HiltViewModel
  class BackupViewModel @Inject constructor(
      private val albumRepository: AlbumRepository,
      private val imageStorage: ImageStorage,
      private val serializer: BackupSerializer,
      private val authManager: DriveAuthManager,
      private val driveRepository: DriveBackupRepository,
      private val prefs: SharedPreferences
  ) : ViewModel() {

      private val _state = MutableStateFlow(
          BackupState(
              accountEmail = authManager.currentAccountEmail(),
              lastBackupTime = prefs.getString(PREF_LAST_BACKUP, null)
          )
      )
      val state: StateFlow<BackupState> = _state.asStateFlow()

      fun signInIntent(): Intent = authManager.signInIntent()

      fun onSignInResult(resultCode: Int, data: Intent?) {
          val account = authManager.handleSignInResult(data)
          _state.update { it.copy(accountEmail = account?.email) }
      }

      fun onSignOut() {
          viewModelScope.launch {
              authManager.signOut()
              _state.update { it.copy(accountEmail = null) }
          }
      }

      fun onBackupClick() {
          viewModelScope.launch(Dispatchers.IO) {
              _state.update { it.copy(isOperationInProgress = true, statusMessage = "") }
              try {
                  val token = authManager.accessToken()
                  val albums = albumRepository.getAllAlbumsWithCopiesOnce()
                  val json = serializer.serialize(albums)
                  val coverImages = albums
                      .mapNotNull { it.album.coverImagePath }
                      .associate { path -> File(path).name to File(path).readBytes() }

                  driveRepository.backup(token, json, coverImages) { msg ->
                      _state.update { it.copy(statusMessage = msg) }
                  }

                  val timestamp = SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault())
                      .format(Date())
                  prefs.edit().putString(PREF_LAST_BACKUP, timestamp).apply()
                  _state.update {
                      it.copy(
                          isOperationInProgress = false,
                          lastBackupTime = timestamp,
                          snackbarMessage = "Backup complete"
                      )
                  }
              } catch (e: Exception) {
                  _state.update {
                      it.copy(
                          isOperationInProgress = false,
                          snackbarMessage = "Backup failed: ${e.message}"
                      )
                  }
              }
          }
      }

      fun onRestoreClick() {
          _state.update { it.copy(showRestoreDialog = true) }
      }

      fun onRestoreConfirmed(mode: RestoreMode) {
          _state.update { it.copy(showRestoreDialog = false) }
          viewModelScope.launch(Dispatchers.IO) {
              _state.update { it.copy(isOperationInProgress = true, statusMessage = "") }
              try {
                  val token = authManager.accessToken()
                  val result = driveRepository.restore(token) { msg ->
                      _state.update { it.copy(statusMessage = msg) }
                  }
                  val backupAlbums = serializer.deserialize(result.json)

                  // Pre-compute which albums to insert (for Merge, filter out existing)
                  val albumsToInsert = if (mode is RestoreMode.Merge) {
                      backupAlbums.filter { !albumRepository.albumExists(it.artist, it.albumName) }
                  } else {
                      backupAlbums
                  }

                  // Save cover images to local storage before touching the DB.
                  // Per-image try-catch: a single failed image does not abort the restore.
                  _state.update { it.copy(statusMessage = "Downloading cover images…") }
                  val coverPaths = albumsToInsert.associate { backupAlbum ->
                      val path = backupAlbum.coverImageFile?.let { filename ->
                          result.coverImages[filename]?.let { bytes ->
                              try { imageStorage.saveImageFromBytes(bytes) } catch (e: Exception) { null }
                          }
                      }
                      backupAlbum to path
                  }

                  // Wrap delete + insert in a single Room transaction so the DB is never
                  // left partially cleared if an insert fails mid-way (Replace path only).
                  var insertedCount = 0
                  albumRepository.withTransaction {
                      if (mode is RestoreMode.Replace) albumRepository.deleteAll()

                      albumsToInsert.forEachIndexed { index, backupAlbum ->
                          _state.update {
                              it.copy(statusMessage = "Restoring album ${index + 1} / ${albumsToInsert.size}…")
                          }
                          val coverPath = coverPaths[backupAlbum]
                          val albumEntity = AlbumEntity(
                              artistName = backupAlbum.artist,
                              albumName = backupAlbum.albumName,
                              numRecords = backupAlbum.numRecords,
                              year = backupAlbum.year,
                              comment = backupAlbum.comment,
                              coverImagePath = coverPath
                          )
                          val firstCopy = backupAlbum.copies.firstOrNull() ?: return@forEachIndexed
                          val albumId = albumRepository.insertAlbumWithCopy(
                              albumEntity,
                              CopyEntity(
                                  albumId = 0,
                                  gradeSide1 = firstCopy.gradeSide1,
                                  gradeSide2 = firstCopy.gradeSide2,
                                  country = firstCopy.country,
                                  listened = firstCopy.listened
                              )
                          )
                          backupAlbum.copies.drop(1).forEach { copy ->
                              albumRepository.insertCopy(
                                  CopyEntity(
                                      albumId = albumId,
                                      gradeSide1 = copy.gradeSide1,
                                      gradeSide2 = copy.gradeSide2,
                                      country = copy.country,
                                      listened = copy.listened
                                  )
                              )
                          }
                          insertedCount++
                      }
                  } // end withTransaction

                  _state.update {
                      it.copy(
                          isOperationInProgress = false,
                          snackbarMessage = "Restored $insertedCount album${if (insertedCount != 1) "s" else ""}"
                      )
                  }
              } catch (e: Exception) {
                  _state.update {
                      it.copy(
                          isOperationInProgress = false,
                          snackbarMessage = "Restore failed: ${e.message}"
                      )
                  }
              }
          }
      }

      fun onRestoreDismissed() {
          _state.update { it.copy(showRestoreDialog = false) }
      }

      fun onSnackbarDismissed() {
          _state.update { it.copy(snackbarMessage = null) }
      }
  }
  ```

- [ ] **Step 2: Verify build**

  ```
  ./gradlew assembleDebug
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/ui/screens/backup/BackupViewModel.kt
  git commit -m "feat: add BackupViewModel for backup and restore state management"
  ```

---

## Task 10: BackupScreen

**Files:**
- Create: `app/src/main/java/com/recordsapp/ui/screens/backup/BackupScreen.kt`

- [ ] **Step 1: Create BackupScreen**

  ```kotlin
  package com.recordsapp.ui.screens.backup

  import androidx.activity.compose.rememberLauncherForActivityResult
  import androidx.activity.result.contract.ActivityResultContracts
  import androidx.compose.foundation.clickable
  import androidx.compose.foundation.layout.*
  import androidx.compose.material.icons.Icons
  import androidx.compose.material.icons.automirrored.filled.ArrowBack
  import androidx.compose.material3.*
  import androidx.compose.runtime.*
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.unit.dp
  import androidx.hilt.navigation.compose.hiltViewModel
  import androidx.lifecycle.compose.collectAsStateWithLifecycle

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun BackupScreen(
      onNavigateBack: () -> Unit,
      viewModel: BackupViewModel = hiltViewModel()
  ) {
      val state by viewModel.state.collectAsStateWithLifecycle()
      val snackbarHostState = remember { SnackbarHostState() }

      val signInLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.StartActivityForResult()
      ) { result ->
          viewModel.onSignInResult(result.resultCode, result.data)
      }

      LaunchedEffect(state.snackbarMessage) {
          state.snackbarMessage?.let {
              snackbarHostState.showSnackbar(it)
              viewModel.onSnackbarDismissed()
          }
      }

      Scaffold(
          topBar = {
              TopAppBar(
                  title = { Text("Backup & Restore") },
                  navigationIcon = {
                      IconButton(onClick = onNavigateBack) {
                          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                      }
                  },
                  colors = TopAppBarDefaults.topAppBarColors(
                      containerColor = MaterialTheme.colorScheme.primaryContainer,
                      titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                  )
              )
          },
          snackbarHost = { SnackbarHost(snackbarHostState) }
      ) { paddingValues ->
          Column(
              modifier = Modifier
                  .fillMaxSize()
                  .padding(paddingValues)
                  .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
              // Google Account card
              Card(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp)) {
                      Text("Google Account", style = MaterialTheme.typography.titleSmall)
                      Spacer(modifier = Modifier.height(8.dp))
                      if (state.accountEmail != null) {
                          Row(
                              modifier = Modifier.fillMaxWidth(),
                              verticalAlignment = Alignment.CenterVertically,
                              horizontalArrangement = Arrangement.SpaceBetween
                          ) {
                              Text(state.accountEmail!!, style = MaterialTheme.typography.bodyMedium)
                              TextButton(onClick = viewModel::onSignOut) { Text("Sign out") }
                          }
                      } else {
                          Button(
                              onClick = { signInLauncher.launch(viewModel.signInIntent()) },
                              modifier = Modifier.fillMaxWidth()
                          ) {
                              Text("Connect Google Account")
                          }
                      }
                  }
              }

              // Progress indicator
              if (state.isOperationInProgress) {
                  Column(modifier = Modifier.fillMaxWidth()) {
                      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                      if (state.statusMessage.isNotEmpty()) {
                          Text(
                              text = state.statusMessage,
                              style = MaterialTheme.typography.bodySmall,
                              modifier = Modifier.padding(top = 4.dp)
                          )
                      }
                  }
              }

              // Backup card
              Card(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp)) {
                      Text("Backup", style = MaterialTheme.typography.titleSmall)
                      Spacer(modifier = Modifier.height(8.dp))
                      Button(
                          onClick = viewModel::onBackupClick,
                          enabled = !state.isOperationInProgress && state.accountEmail != null,
                          modifier = Modifier.fillMaxWidth()
                      ) {
                          Text("Backup to Google Drive")
                      }
                      Text(
                          text = state.lastBackupTime?.let { "Last backup: $it" } ?: "Never backed up",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.padding(top = 4.dp)
                      )
                  }
              }

              // Restore card
              Card(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp)) {
                      Text("Restore", style = MaterialTheme.typography.titleSmall)
                      Spacer(modifier = Modifier.height(8.dp))
                      OutlinedButton(
                          onClick = viewModel::onRestoreClick,
                          enabled = !state.isOperationInProgress && state.accountEmail != null,
                          modifier = Modifier.fillMaxWidth()
                      ) {
                          Text("Restore from Google Drive")
                      }
                  }
              }
          }
      }

      if (state.showRestoreDialog) {
          RestoreDialog(
              onConfirm = viewModel::onRestoreConfirmed,
              onDismiss = viewModel::onRestoreDismissed
          )
      }
  }

  @Composable
  private fun RestoreDialog(
      onConfirm: (RestoreMode) -> Unit,
      onDismiss: () -> Unit
  ) {
      var selectedMode by remember { mutableStateOf<RestoreMode>(RestoreMode.Replace) }

      AlertDialog(
          onDismissRequest = onDismiss,
          title = { Text("Restore backup?") },
          text = {
              Column {
                  RestoreOption(
                      label = "Replace",
                      description = "Delete all current records and replace with backup",
                      selected = selectedMode is RestoreMode.Replace,
                      onClick = { selectedMode = RestoreMode.Replace }
                  )
                  RestoreOption(
                      label = "Merge",
                      description = "Add albums from backup that don't already exist locally",
                      selected = selectedMode is RestoreMode.Merge,
                      onClick = { selectedMode = RestoreMode.Merge }
                  )
              }
          },
          confirmButton = {
              Button(onClick = { onConfirm(selectedMode) }) { Text("Restore") }
          },
          dismissButton = {
              TextButton(onClick = onDismiss) { Text("Cancel") }
          }
      )
  }

  @Composable
  private fun RestoreOption(
      label: String,
      description: String,
      selected: Boolean,
      onClick: () -> Unit
  ) {
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .clickable(onClick = onClick)
              .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically
      ) {
          RadioButton(selected = selected, onClick = onClick)
          Column(modifier = Modifier.padding(start = 8.dp)) {
              Text(label, style = MaterialTheme.typography.bodyLarge)
              Text(
                  description,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
              )
          }
      }
  }
  ```

- [ ] **Step 2: Verify build**

  ```
  ./gradlew assembleDebug
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/ui/screens/backup/BackupScreen.kt
  git commit -m "feat: add BackupScreen with account, backup, and restore UI"
  ```

---

## Task 11: Wire Navigation

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/recordsapp/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/recordsapp/ui/screens/albumlist/AlbumListScreen.kt`

- [ ] **Step 1: Add Backup to Screen sealed class**

  In `Screen.kt`, add after the `AddCopy` entry:

  ```kotlin
  data object Backup : Screen("backup")
  ```

- [ ] **Step 2: Add BackupScreen composable to NavGraph**

  In `NavGraph.kt`, add these imports:
  ```kotlin
  import com.recordsapp.ui.screens.backup.BackupScreen
  ```

  Inside the `NavHost` block, after the `AddCopy` composable, add:
  ```kotlin
  composable(Screen.Backup.route) {
      BackupScreen(
          onNavigateBack = { navController.popBackStack() }
      )
  }
  ```

- [ ] **Step 3: Add settings icon and callback to AlbumListScreen**

  In `AlbumListScreen.kt`, update the function signature to add `onSettingsClick`:

  ```kotlin
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun AlbumListScreen(
      onAlbumClick: (Long) -> Unit,
      onAddAlbumClick: () -> Unit,
      onSettingsClick: () -> Unit,
      viewModel: AlbumListViewModel = hiltViewModel()
  )
  ```

  Update the `TopAppBar` call to add `actions`:

  ```kotlin
  TopAppBar(
      title = { Text("My Records (${albums.size})") },
      colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
      ),
      actions = {
          IconButton(onClick = onSettingsClick) {
              Icon(
                  imageVector = Icons.Default.Settings,
                  contentDescription = "Backup & Restore",
                  tint = MaterialTheme.colorScheme.onPrimaryContainer
              )
          }
      }
  )
  ```

  Add this import at the top of `AlbumListScreen.kt`:
  ```kotlin
  import androidx.compose.material.icons.filled.Settings
  ```

- [ ] **Step 4: Pass onSettingsClick in NavGraph**

  In `NavGraph.kt`, update the `AlbumList` composable call:

  ```kotlin
  composable(Screen.AlbumList.route) {
      AlbumListScreen(
          onAlbumClick = { albumId ->
              navController.navigate(Screen.AlbumDetail.createRoute(albumId))
          },
          onAddAlbumClick = {
              navController.navigate(Screen.AddAlbum.route)
          },
          onSettingsClick = {
              navController.navigate(Screen.Backup.route)
          }
      )
  }
  ```

- [ ] **Step 5: Final build and full test run**

  ```
  ./gradlew assembleDebug test
  ```
  Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/recordsapp/ui/navigation/Screen.kt \
          app/src/main/java/com/recordsapp/ui/navigation/NavGraph.kt \
          app/src/main/java/com/recordsapp/ui/screens/albumlist/AlbumListScreen.kt
  git commit -m "feat: wire BackupScreen into navigation with settings icon on album list"
  ```

---

## Manual Smoke Test Checklist

After all tasks pass build:

- [ ] Install on device: `./gradlew installDebug`
- [ ] Tap the settings icon on the album list — `BackupScreen` opens
- [ ] Tap "Connect Google Account" — Google Sign-In sheet appears, sign in succeeds, email shows
- [ ] Tap "Backup to Google Drive" — progress indicator shows, completes with "Backup complete" snackbar, "Last backup" timestamp appears
- [ ] Open Google Drive on desktop — verify `RecordsApp/backup.json` and cover image files are present
- [ ] Tap "Restore from Google Drive" — dialog shows Replace/Merge options, confirming runs restore, "Restored N albums" snackbar appears
- [ ] Tap "Sign out" — email clears, buttons disable
