# Record Recognition via AI Vision — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the user takes or picks a cover photo in the add-album flow, call the Gemini API to identify the record and show a bottom sheet where the user can accept, reject, or retake.

**Architecture:** `GeminiRecognitionService` handles the HTTP call and response parsing; it is injected into `AddEditAlbumViewModel` via a new Hilt `NetworkModule`. The ViewModel holds `recognitionState: StateFlow<RecognitionState>` and drives a `RecordRecognitionBottomSheet` composable shown over the existing `AddEditAlbumScreen`.

**Tech Stack:** Gemini 1.5 Flash REST API, OkHttp 4.12, `org.json.JSONObject` (built-in Android), Hilt, Jetpack Compose `ModalBottomSheet`, `kotlinx-coroutines-test` + MockK for tests.

---

## File Map

**New files:**
- `app/src/main/java/com/recordsapp/domain/model/RecognitionResult.kt` — `RecognitionResult` data class + `Confidence` enum
- `app/src/main/java/com/recordsapp/data/remote/RecognitionService.kt` — interface (enables fake injection in tests)
- `app/src/main/java/com/recordsapp/data/remote/GeminiRecognitionService.kt` — implementation + top-level `parseGeminiResponse()` function
- `app/src/main/java/com/recordsapp/di/NetworkModule.kt` — Hilt module binding `GeminiRecognitionService` to `RecognitionService`
- `app/src/main/java/com/recordsapp/ui/components/RecordRecognitionBottomSheet.kt` — `ModalBottomSheet` composable
- `app/src/test/java/com/recordsapp/data/remote/GeminiRecognitionServiceTest.kt` — unit tests for `parseGeminiResponse()`
- `app/src/test/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModelRecognitionTest.kt` — ViewModel recognition state tests

**Modified files:**
- `gradle/libs.versions.toml` — add `okhttp` version
- `app/build.gradle.kts` — add OkHttp dep, `buildConfig = true`, `buildConfigField`, test deps
- `app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModel.kt` — add `RecognitionState`, recognition methods, `retakeRequested` flow
- `app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumScreen.kt` — show bottom sheet, observe `retakeRequested`
- `app/src/main/java/com/recordsapp/ui/components/CoverImagePicker.kt` — add `launchCamera`/`onCameraLaunched` params

---

## Task 1: Configure Build & API Key

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `local.properties` (manual — not committed)

- [ ] **Step 1: Add OkHttp to version catalog**

In `gradle/libs.versions.toml`, add under `[versions]`:
```toml
okhttp = "4.12.0"
```
And under `[libraries]`:
```toml
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
```

- [ ] **Step 2: Replace `app/build.gradle.kts` with the following complete file**

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().also { props ->
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
}

android {
    namespace = "com.recordsapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.recordsapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String", "GEMINI_API_KEY",
            "\"${localProperties.getProperty("gemini_api_key", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.coil.compose)

    implementation(libs.camerax.core)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.okhttp)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.12")
}
```

- [ ] **Step 3: Add API key to `local.properties` (manual)**

Open `local.properties` (project root, already git-ignored) and add:
```
gemini_api_key=YOUR_KEY_HERE
```
Get a free key at https://aistudio.google.com → "Get API key".

- [ ] **Step 4: Verify build compiles**

```bash
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`. If it says `gemini_api_key` is empty, check `local.properties` path.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add OkHttp, BuildConfig API key, and test deps for record recognition"
```

---

## Task 2: Domain Models

**Files:**
- Create: `app/src/main/java/com/recordsapp/domain/model/RecognitionResult.kt`

- [ ] **Step 1: Create `RecognitionResult.kt`**

```kotlin
package com.recordsapp.domain.model

data class RecognitionResult(
    val artistName: String,
    val albumName: String,
    val year: String,
    val numRecords: String,
    val confidence: Confidence
)

enum class Confidence { HIGH, LOW }
```

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/recordsapp/domain/model/RecognitionResult.kt
git commit -m "feat: add RecognitionResult domain model"
```

---

## Task 3: RecognitionService Interface + GeminiRecognitionService

**Files:**
- Create: `app/src/main/java/com/recordsapp/data/remote/RecognitionService.kt`
- Create: `app/src/main/java/com/recordsapp/data/remote/GeminiRecognitionService.kt`
- Create: `app/src/test/java/com/recordsapp/data/remote/GeminiRecognitionServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/recordsapp/data/remote/GeminiRecognitionServiceTest.kt`:

```kotlin
package com.recordsapp.data.remote

import com.recordsapp.domain.model.Confidence
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiRecognitionServiceTest {

    @Test
    fun `parseGeminiResponse extracts all fields from clean JSON`() {
        val json = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\"artistName\":\"Pink Floyd\",\"albumName\":\"The Wall\",\"year\":\"1979\",\"numRecords\":\"2\",\"confidence\":\"high\"}"
              }]
            }
          }]
        }
        """.trimIndent()

        val result = parseGeminiResponse(json)

        assertEquals("Pink Floyd", result.artistName)
        assertEquals("The Wall", result.albumName)
        assertEquals("1979", result.year)
        assertEquals("2", result.numRecords)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun `parseGeminiResponse strips markdown code fences`() {
        val json = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "```json\n{\"artistName\":\"Led Zeppelin\",\"albumName\":\"IV\",\"year\":\"1971\",\"numRecords\":\"1\",\"confidence\":\"low\"}\n```"
              }]
            }
          }]
        }
        """.trimIndent()

        val result = parseGeminiResponse(json)

        assertEquals("Led Zeppelin", result.artistName)
        assertEquals("IV", result.albumName)
        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun `parseGeminiResponse defaults unknown confidence to LOW`() {
        val json = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\"artistName\":\"\",\"albumName\":\"\",\"year\":\"\",\"numRecords\":\"\",\"confidence\":\"medium\"}"
              }]
            }
          }]
        }
        """.trimIndent()

        val result = parseGeminiResponse(json)

        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun `parseGeminiResponse returns empty strings for missing fields`() {
        val json = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\"confidence\":\"low\"}"
              }]
            }
          }]
        }
        """.trimIndent()

        val result = parseGeminiResponse(json)

        assertEquals("", result.artistName)
        assertEquals("", result.albumName)
        assertEquals("", result.year)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.recordsapp.data.remote.GeminiRecognitionServiceTest"
```
Expected: FAIL — `parseGeminiResponse` not defined.

- [ ] **Step 3: Create `RecognitionService.kt`**

```kotlin
package com.recordsapp.data.remote

import android.net.Uri
import com.recordsapp.domain.model.RecognitionResult

interface RecognitionService {
    suspend fun recognize(uri: Uri): RecognitionResult
}
```

- [ ] **Step 4: Create `GeminiRecognitionService.kt`**

```kotlin
package com.recordsapp.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.recordsapp.BuildConfig
import com.recordsapp.domain.model.Confidence
import com.recordsapp.domain.model.RecognitionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiRecognitionService @Inject constructor(
    @ApplicationContext private val context: Context
) : RecognitionService {

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(uri: Uri): RecognitionResult = withContext(Dispatchers.IO) {
        val imageBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot open URI: $uri")
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", PROMPT) })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw GeminiApiException(response.code)
        }
        val body = response.body?.string() ?: throw IllegalStateException("Empty response")
        parseGeminiResponse(body)
    }

    companion object {
        private const val PROMPT = """You are identifying a vinyl record from its cover photo.
Return ONLY a JSON object with these fields:
{
  "artistName": "...",
  "albumName": "...",
  "year": "...",
  "numRecords": "...",
  "confidence": "high" or "low"
}
Use "low" confidence if the cover is unclear, partially visible, or you are not certain.
If a field cannot be determined, use an empty string."""
    }
}

class GeminiApiException(val code: Int) : Exception("Gemini API error: $code")

internal fun parseGeminiResponse(json: String): RecognitionResult {
    val text = JSONObject(json)
        .getJSONArray("candidates")
        .getJSONObject(0)
        .getJSONObject("content")
        .getJSONArray("parts")
        .getJSONObject(0)
        .getString("text")

    val cleaned = text.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    val result = JSONObject(cleaned)
    return RecognitionResult(
        artistName = result.optString("artistName", ""),
        albumName = result.optString("albumName", ""),
        year = result.optString("year", ""),
        numRecords = result.optString("numRecords", ""),
        confidence = if (result.optString("confidence", "low") == "high") Confidence.HIGH else Confidence.LOW
    )
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "com.recordsapp.data.remote.GeminiRecognitionServiceTest"
```
Expected: 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/recordsapp/data/remote/ \
        app/src/test/java/com/recordsapp/data/remote/
git commit -m "feat: add RecognitionService and GeminiRecognitionService"
```

---

## Task 4: Hilt NetworkModule

**Files:**
- Create: `app/src/main/java/com/recordsapp/di/NetworkModule.kt`

- [ ] **Step 1: Create `NetworkModule.kt`**

```kotlin
package com.recordsapp.di

import com.recordsapp.data.remote.GeminiRecognitionService
import com.recordsapp.data.remote.RecognitionService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds
    @Singleton
    abstract fun bindRecognitionService(impl: GeminiRecognitionService): RecognitionService
}
```

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/recordsapp/di/NetworkModule.kt
git commit -m "feat: add NetworkModule for RecognitionService DI"
```

---

## Task 5: Update AddEditAlbumViewModel

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModel.kt`
- Create: `app/src/test/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModelRecognitionTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModelRecognitionTest.kt`:

```kotlin
package com.recordsapp.ui.screens.addeditalbum

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.recordsapp.data.local.ImageStorage
import com.recordsapp.data.remote.RecognitionService
import com.recordsapp.data.repository.AlbumRepository
import com.recordsapp.domain.model.Confidence
import com.recordsapp.domain.model.RecognitionResult
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditAlbumViewModelRecognitionTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val fakeService = FakeRecognitionService()
    private val repository = mockk<AlbumRepository>(relaxed = true)
    private val imageStorage = mockk<ImageStorage>(relaxed = true)
    private val uri = mockk<Uri>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = AddEditAlbumViewModel(
        savedStateHandle = SavedStateHandle(),
        repository = repository,
        imageStorage = imageStorage,
        recognitionService = fakeService
    )

    @Test
    fun `onCoverImageChanged sets recognitionState to Result on success`() = runTest {
        fakeService.result = RecognitionResult("Pink Floyd", "The Wall", "1979", "2", Confidence.HIGH)
        val viewModel = createViewModel()

        viewModel.onCoverImageChanged(uri)

        val state = viewModel.state.value
        assertTrue(state.recognitionState is RecognitionState.Result)
        val result = (state.recognitionState as RecognitionState.Result).result
        assertEquals("Pink Floyd", result.artistName)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun `onCoverImageChanged sets recognitionState to Error on failure`() = runTest {
        fakeService.shouldThrow = RuntimeException("network error")
        val viewModel = createViewModel()

        viewModel.onCoverImageChanged(uri)

        assertTrue(viewModel.state.value.recognitionState is RecognitionState.Error)
    }

    @Test
    fun `acceptRecognition fills form fields and clears recognitionState`() = runTest {
        fakeService.result = RecognitionResult("Led Zeppelin", "IV", "1971", "1", Confidence.LOW)
        val viewModel = createViewModel()
        viewModel.onCoverImageChanged(uri)

        viewModel.acceptRecognition()

        val state = viewModel.state.value
        assertEquals("Led Zeppelin", state.artistName)
        assertEquals("IV", state.albumName)
        assertEquals("1971", state.year)
        assertEquals("1", state.numRecords)
        assertEquals(RecognitionState.Idle, state.recognitionState)
    }

    @Test
    fun `rejectRecognition clears recognitionState without filling fields`() = runTest {
        fakeService.result = RecognitionResult("Led Zeppelin", "IV", "1971", "1", Confidence.HIGH)
        val viewModel = createViewModel()
        viewModel.onCoverImageChanged(uri)

        viewModel.rejectRecognition()

        val state = viewModel.state.value
        assertEquals("", state.artistName)
        assertEquals(RecognitionState.Idle, state.recognitionState)
    }

    @Test
    fun `retakePhoto clears recognitionState and coverImageUri`() = runTest {
        fakeService.result = RecognitionResult("", "", "", "", Confidence.LOW)
        val viewModel = createViewModel()
        viewModel.onCoverImageChanged(uri)

        viewModel.retakePhoto()

        val state = viewModel.state.value
        assertEquals(RecognitionState.Idle, state.recognitionState)
        assertEquals(null, state.coverImageUri)
    }

    @Test
    fun `retakePhoto emits retakeRequested event`() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<Unit>()
        val job = kotlinx.coroutines.launch(testDispatcher) {
            viewModel.retakeRequested.collect { events.add(it) }
        }

        viewModel.retakePhoto()

        assertEquals(1, events.size)
        job.cancel()
    }
}

class FakeRecognitionService : RecognitionService {
    var result = RecognitionResult("", "", "", "", Confidence.HIGH)
    var shouldThrow: Exception? = null

    override suspend fun recognize(uri: Uri): RecognitionResult {
        shouldThrow?.let { throw it }
        return result
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.recordsapp.ui.screens.addeditalbum.AddEditAlbumViewModelRecognitionTest"
```
Expected: FAIL — `RecognitionState`, `recognitionService`, and recognition methods not yet defined.

- [ ] **Step 3: Update `AddEditAlbumViewModel.kt`**

Replace the entire file content:

```kotlin
package com.recordsapp.ui.screens.addeditalbum

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recordsapp.data.local.ImageStorage
import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.entity.CopyEntity
import com.recordsapp.data.remote.GeminiApiException
import com.recordsapp.data.remote.RecognitionService
import com.recordsapp.data.repository.AlbumRepository
import com.recordsapp.domain.model.Country
import com.recordsapp.domain.model.Grade
import com.recordsapp.domain.model.RecognitionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

sealed class RecognitionState {
    object Idle : RecognitionState()
    object Loading : RecognitionState()
    data class Result(val result: RecognitionResult) : RecognitionState()
    data class Error(val message: String) : RecognitionState()
}

data class AddEditAlbumState(
    val artistName: String = "",
    val albumName: String = "",
    val numRecords: String = "1",
    val year: String = "",
    val coverImageUri: Uri? = null,
    val comment: String = "",
    val gradeSide1: Grade? = null,
    val gradeSide2: Grade? = null,
    val country: Country? = null,
    val listened: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val existingCoverPath: String? = null,
    val recognitionState: RecognitionState = RecognitionState.Idle
)

@HiltViewModel
class AddEditAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AlbumRepository,
    private val imageStorage: ImageStorage,
    private val recognitionService: RecognitionService
) : ViewModel() {

    private val albumId: Long? = savedStateHandle.get<Long>("albumId")

    private val _state = MutableStateFlow(AddEditAlbumState())
    val state: StateFlow<AddEditAlbumState> = _state.asStateFlow()

    private val _saveComplete = MutableSharedFlow<Boolean>()
    val saveComplete: SharedFlow<Boolean> = _saveComplete.asSharedFlow()

    private val _retakeRequested = MutableSharedFlow<Unit>()
    val retakeRequested: SharedFlow<Unit> = _retakeRequested.asSharedFlow()

    init {
        if (albumId != null) {
            viewModelScope.launch {
                repository.getAlbumWithCopies(albumId).first()?.let { awc ->
                    val firstCopy = awc.copies.firstOrNull()
                    _state.value = AddEditAlbumState(
                        artistName = awc.album.artistName,
                        albumName = awc.album.albumName,
                        numRecords = awc.album.numRecords.toString(),
                        year = awc.album.year.toString(),
                        comment = awc.album.comment,
                        existingCoverPath = awc.album.coverImagePath,
                        gradeSide1 = firstCopy?.let {
                            Grade.entries.find { g -> g.displayName == it.gradeSide1 }
                        },
                        gradeSide2 = firstCopy?.let {
                            Grade.entries.find { g -> g.displayName == it.gradeSide2 }
                        },
                        country = firstCopy?.let {
                            Country.entries.find { c -> c.displayName == it.country }
                        },
                        listened = firstCopy?.listened ?: false,
                        isEditing = true
                    )
                }
            }
        }
    }

    fun onArtistNameChanged(value: String) { _state.update { it.copy(artistName = value) } }
    fun onAlbumNameChanged(value: String) { _state.update { it.copy(albumName = value) } }
    fun onNumRecordsChanged(value: String) { _state.update { it.copy(numRecords = value) } }
    fun onYearChanged(value: String) { _state.update { it.copy(year = value) } }
    fun onCommentChanged(value: String) { _state.update { it.copy(comment = value) } }
    fun onGradeSide1Changed(grade: Grade) { _state.update { it.copy(gradeSide1 = grade) } }
    fun onGradeSide2Changed(grade: Grade) { _state.update { it.copy(gradeSide2 = grade) } }
    fun onCountryChanged(country: Country) { _state.update { it.copy(country = country) } }
    fun onListenedChanged(value: Boolean) { _state.update { it.copy(listened = value) } }

    fun onCoverImageChanged(uri: Uri) {
        _state.update { it.copy(coverImageUri = uri) }
        recognizeRecord(uri)
    }

    private fun recognizeRecord(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(recognitionState = RecognitionState.Loading) }
            try {
                val result = recognitionService.recognize(uri)
                _state.update { it.copy(recognitionState = RecognitionState.Result(result)) }
            } catch (e: Exception) {
                val message = when {
                    e is UnknownHostException -> "No internet connection. Retake or fill manually."
                    e is GeminiApiException && e.code == 403 -> "Recognition unavailable."
                    e is SocketTimeoutException -> "Recognition timed out."
                    else -> "Couldn't read the result."
                }
                _state.update { it.copy(recognitionState = RecognitionState.Error(message)) }
            }
        }
    }

    fun acceptRecognition() {
        val result = (_state.value.recognitionState as? RecognitionState.Result)?.result ?: return
        _state.update { state ->
            state.copy(
                artistName = result.artistName.ifBlank { state.artistName },
                albumName = result.albumName.ifBlank { state.albumName },
                year = result.year.ifBlank { state.year },
                numRecords = result.numRecords.ifBlank { state.numRecords },
                recognitionState = RecognitionState.Idle
            )
        }
    }

    fun rejectRecognition() {
        _state.update { it.copy(recognitionState = RecognitionState.Idle) }
    }

    fun retakePhoto() {
        _state.update { it.copy(recognitionState = RecognitionState.Idle, coverImageUri = null) }
        viewModelScope.launch { _retakeRequested.emit(Unit) }
    }

    fun save() {
        val current = _state.value
        if (current.artistName.isBlank() || current.albumName.isBlank()) return
        if (!current.isEditing &&
            (current.gradeSide1 == null || current.gradeSide2 == null || current.country == null)
        ) return

        _state.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val coverPath = if (current.coverImageUri != null) {
                imageStorage.saveImageFromUri(current.coverImageUri)
            } else {
                current.existingCoverPath
            }

            if (current.isEditing && albumId != null) {
                val album = AlbumEntity(
                    id = albumId,
                    artistName = current.artistName.trim(),
                    albumName = current.albumName.trim(),
                    numRecords = current.numRecords.toIntOrNull() ?: 1,
                    year = current.year.toIntOrNull() ?: 0,
                    coverImagePath = coverPath,
                    comment = current.comment.trim()
                )
                repository.updateAlbum(album)
            } else {
                val album = AlbumEntity(
                    artistName = current.artistName.trim(),
                    albumName = current.albumName.trim(),
                    numRecords = current.numRecords.toIntOrNull() ?: 1,
                    year = current.year.toIntOrNull() ?: 0,
                    coverImagePath = coverPath,
                    comment = current.comment.trim()
                )
                val copy = CopyEntity(
                    albumId = 0,
                    gradeSide1 = current.gradeSide1!!.displayName,
                    gradeSide2 = current.gradeSide2!!.displayName,
                    country = current.country!!.displayName,
                    listened = current.listened
                )
                repository.insertAlbumWithCopy(album, copy)
            }
            _state.update { it.copy(isSaving = false) }
            _saveComplete.emit(true)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.recordsapp.ui.screens.addeditalbum.AddEditAlbumViewModelRecognitionTest"
```
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumViewModel.kt \
        app/src/test/java/com/recordsapp/ui/screens/addeditalbum/
git commit -m "feat: add recognition state and methods to AddEditAlbumViewModel"
```

---

## Task 6: Update CoverImagePicker

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/components/CoverImagePicker.kt`

- [ ] **Step 1: Add `launchCamera` and `onCameraLaunched` parameters**

Replace the `CoverImagePicker` function signature and add a `LaunchedEffect` to auto-launch the camera when `launchCamera = true`. Replace the existing composable:

```kotlin
@Composable
fun CoverImagePicker(
    currentImageUri: Uri?,
    onImagePicked: (Uri) -> Unit,
    launchCamera: Boolean = false,
    onCameraLaunched: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImagePicked(it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) pendingCameraUri?.let { onImagePicked(it) }
    }

    LaunchedEffect(launchCamera) {
        if (launchCamera) {
            val uri = createTempCameraUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
            onCameraLaunched()
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Add Cover Photo") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showDialog = false
                            galleryLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("Choose from Gallery")
                        }
                    }
                    TextButton(
                        onClick = {
                            showDialog = false
                            val uri = createTempCameraUri(context)
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("Take Photo")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (currentImageUri != null) {
        AsyncImage(
            model = currentImageUri,
            contentDescription = "Album cover",
            modifier = modifier
                .size(180.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable { showDialog = true },
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = modifier
                .size(180.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable { showDialog = true },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AddAPhoto,
                    contentDescription = "Add cover photo",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Add Cover",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/recordsapp/ui/components/CoverImagePicker.kt
git commit -m "feat: add launchCamera param to CoverImagePicker for retake support"
```

---

## Task 7: Create RecordRecognitionBottomSheet

**Files:**
- Create: `app/src/main/java/com/recordsapp/ui/components/RecordRecognitionBottomSheet.kt`

- [ ] **Step 1: Create `RecordRecognitionBottomSheet.kt`**

```kotlin
package com.recordsapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.recordsapp.domain.model.Confidence
import com.recordsapp.ui.screens.addeditalbum.RecognitionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordRecognitionBottomSheet(
    recognitionState: RecognitionState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onRetake: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        when (recognitionState) {
            is RecognitionState.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Identifying record…", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                }
            }

            is RecognitionState.Result -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Record Identified", style = MaterialTheme.typography.titleLarge)

                    if (recognitionState.result.confidence == Confidence.LOW) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                "Low confidence — please verify",
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    HorizontalDivider()

                    RecognitionField("Artist", recognitionState.result.artistName)
                    RecognitionField("Album", recognitionState.result.albumName)
                    RecognitionField("Year", recognitionState.result.year)
                    RecognitionField("Records", recognitionState.result.numRecords)

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
                        Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
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

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/recordsapp/ui/components/RecordRecognitionBottomSheet.kt
git commit -m "feat: add RecordRecognitionBottomSheet composable"
```

---

## Task 8: Update AddEditAlbumScreen

**Files:**
- Modify: `app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumScreen.kt`

- [ ] **Step 1: Wire the bottom sheet into `AddEditAlbumScreen`**

Replace the entire file:

```kotlin
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

    val showBottomSheet = state.recognitionState != RecognitionState.Idle

    if (showBottomSheet) {
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
```

- [ ] **Step 2: Run all tests**

```bash
./gradlew test
```
Expected: All tests PASS.

- [ ] **Step 3: Build and install on device**

```bash
./gradlew installDebug
```

- [ ] **Step 4: Manual test on device**

1. Open the app → tap "+" to add an album
2. Tap the cover image placeholder → choose "Take Photo" → photograph a record cover
3. Verify the bottom sheet appears with a loading spinner, then shows the found details
4. Tap **Accept** → verify artist, album, year, and num records fields are filled
5. Add another album → take photo → tap **Reject** → verify fields stay empty
6. Add another album → take photo → tap **Retake** → verify camera opens again
7. Test with a blurry/unclear photo → verify "Low confidence — please verify" warning appears

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/recordsapp/ui/screens/addeditalbum/AddEditAlbumScreen.kt
git commit -m "feat: wire RecordRecognitionBottomSheet into AddEditAlbumScreen"
```
