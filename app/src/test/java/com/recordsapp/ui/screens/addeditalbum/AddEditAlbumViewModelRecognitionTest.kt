package com.recordsapp.ui.screens.addeditalbum

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.recordsapp.data.local.ImageStorage
import com.recordsapp.data.local.QaExporter
import com.recordsapp.data.remote.CoverArtMatchService
import com.recordsapp.data.remote.CoverMatchResult
import com.recordsapp.data.remote.ItunesCoverArtService
import com.recordsapp.data.remote.RecognitionService
import com.recordsapp.data.repository.AlbumRepository
import com.recordsapp.domain.model.Confidence
import com.recordsapp.domain.model.RecognitionResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditAlbumViewModelRecognitionTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val fakeService = FakeRecognitionService()
    private val repository = mockk<AlbumRepository>(relaxed = true)
    private val imageStorage = mockk<ImageStorage>(relaxed = true)
    private val coverArtService = mockk<ItunesCoverArtService>(relaxed = true)
    private val coverArtMatchService = mockk<CoverArtMatchService>(relaxed = true)
    private val qaExporter = mockk<QaExporter>(relaxed = true)
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
        recognitionService = fakeService,
        coverArtService = coverArtService,
        coverArtMatchService = coverArtMatchService,
        qaExporter = qaExporter
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

        viewModel.acceptRecognition(selectedCoverUrl = null)

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
    fun `onCoverImageChanged reorders coverArtUrls per the AI ranking`() = runTest {
        fakeService.result = RecognitionResult("Pink Floyd", "The Wall", "1979", "2", Confidence.HIGH)
        val urls = listOf("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg")
        coEvery { coverArtService.fetchUrls("Pink Floyd", "The Wall") } returns urls
        coEvery { coverArtMatchService.rankCandidates(uri, urls) } returns CoverMatchResult(
            rankedIndices = listOf(2, 0, 1),
            bestIsGoodMatch = true
        )
        val viewModel = createViewModel()

        viewModel.onCoverImageChanged(uri)

        val result = viewModel.state.value.recognitionState as RecognitionState.Result
        assertEquals(listOf(urls[2], urls[0], urls[1]), result.coverArtUrls)
        assertTrue(result.bestCoverIsGoodMatch)
        assertFalse(result.isRankingCovers)
    }

    @Test
    fun `onCoverImageChanged reports no good match when the AI is unsure`() = runTest {
        fakeService.result = RecognitionResult("Pink Floyd", "The Wall", "1979", "2", Confidence.HIGH)
        val urls = listOf("https://example.com/a.jpg")
        coEvery { coverArtService.fetchUrls("Pink Floyd", "The Wall") } returns urls
        coEvery { coverArtMatchService.rankCandidates(uri, urls) } returns CoverMatchResult(
            rankedIndices = listOf(0),
            bestIsGoodMatch = false
        )
        val viewModel = createViewModel()

        viewModel.onCoverImageChanged(uri)

        val result = viewModel.state.value.recognitionState as RecognitionState.Result
        assertFalse(result.bestCoverIsGoodMatch)
    }

    @Test
    fun `onCoverImageChanged keeps iTunes order when ranking throws`() = runTest {
        fakeService.result = RecognitionResult("Pink Floyd", "The Wall", "1979", "2", Confidence.HIGH)
        val urls = listOf("https://example.com/a.jpg", "https://example.com/b.jpg")
        coEvery { coverArtService.fetchUrls("Pink Floyd", "The Wall") } returns urls
        coEvery { coverArtMatchService.rankCandidates(uri, urls) } throws RuntimeException("boom")
        val viewModel = createViewModel()

        viewModel.onCoverImageChanged(uri)

        val result = viewModel.state.value.recognitionState as RecognitionState.Result
        assertEquals(urls, result.coverArtUrls)
        assertTrue(result.bestCoverIsGoodMatch)
    }

    @Test
    fun `isRankingCovers is true while the AI comparison is in flight`() = runTest {
        fakeService.result = RecognitionResult("Pink Floyd", "The Wall", "1979", "2", Confidence.HIGH)
        val urls = listOf("https://example.com/a.jpg")
        coEvery { coverArtService.fetchUrls("Pink Floyd", "The Wall") } returns urls
        val gate = CompletableDeferred<CoverMatchResult>()
        coEvery { coverArtMatchService.rankCandidates(uri, urls) } coAnswers { gate.await() }
        val viewModel = createViewModel()

        viewModel.onCoverImageChanged(uri)

        val duringRanking = viewModel.state.value.recognitionState as RecognitionState.Result
        assertTrue(duringRanking.isRankingCovers)

        gate.complete(CoverMatchResult(listOf(0), true))
        advanceUntilIdle()

        val afterRanking = viewModel.state.value.recognitionState as RecognitionState.Result
        assertFalse(afterRanking.isRankingCovers)
    }

    @Test
    fun `retakePhoto emits retakeRequested event`() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<Unit>()
        val job = launch(testDispatcher) {
            viewModel.retakeRequested.collect { events.add(it) }
        }

        viewModel.retakePhoto()
        advanceUntilIdle()

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
