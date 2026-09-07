package com.recordsapp.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.recordsapp.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** Best-to-worst order of `candidateUrls` indices, plus whether the top pick is trustworthy. */
data class CoverMatchResult(val rankedIndices: List<Int>, val bestIsGoodMatch: Boolean)

/**
 * Compares the photo the user took against candidate album-art images found
 * via [ItunesCoverArtService] and asks Gemini which one is the same print
 * edition — so the app can default to a verified match instead of iTunes's
 * raw search ranking.
 */
@Singleton
class CoverArtMatchService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Ranks [candidateUrls] against the photo at [photoUri]. Never throws —
     * on any failure (network, parsing, a candidate that won't download) it
     * falls back to the original iTunes order so a broken ranking call never
     * blocks accepting the record.
     */
    suspend fun rankCandidates(photoUri: Uri, candidateUrls: List<String>): CoverMatchResult {
        if (candidateUrls.isEmpty()) return CoverMatchResult(emptyList(), bestIsGoodMatch = false)
        return try {
            withContext(Dispatchers.IO) {
                val photoBytes = ImageCompression.fromUri(context, photoUri, MAX_DIM, quality = 85)
                val candidateBytes = candidateUrls.map { url -> async { downloadImage(url) } }.awaitAll()

                // A candidate that failed to download can't be judged — drop it and
                // remember the mapping back to the original index.
                val available = candidateBytes.withIndex().mapNotNull { (i, bytes) ->
                    bytes?.let { i to it }
                }
                if (available.isEmpty()) return@withContext identityFallback(candidateUrls.size)

                val body = buildRequestBody(photoBytes, available.map { it.second })
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val responseText = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext identityFallback(candidateUrls.size)
                    resp.body?.string()
                } ?: return@withContext identityFallback(candidateUrls.size)

                val parsed = parseCoverMatchResponse(responseText, available.size)
                // Map ranked positions (indices into `available`) back to indices
                // into the original `candidateUrls` list.
                val originalIndices = parsed.rankedIndices.mapNotNull { available.getOrNull(it)?.first }
                val remaining = candidateUrls.indices.filter { it !in originalIndices }
                CoverMatchResult(originalIndices + remaining, parsed.bestIsGoodMatch)
            }
        } catch (e: Exception) {
            identityFallback(candidateUrls.size)
        }
    }

    private fun identityFallback(count: Int) = CoverMatchResult(List(count) { it }, bestIsGoodMatch = count > 0)

    private fun downloadImage(url: String): ByteArray? = try {
        // Discogs's image CDN (i.discogs.com) returns 403 for OkHttp's
        // default User-Agent -- confirmed by testing directly. Candidates
        // from DiscogsCoverArtService need this to download at all.
        val request = Request.Builder().url(url).header("User-Agent", "RecordsApp/1.0").build()
        downloadClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()?.let {
                ImageCompression.fromBytes(it, CANDIDATE_MAX_DIM, quality = 80)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun buildRequestBody(photoBytes: ByteArray, candidates: List<ByteArray>): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().apply { put("text", PROMPT) })
        parts.put(imagePart(photoBytes))
        candidates.forEachIndexed { i, bytes ->
            parts.put(JSONObject().apply { put("text", "Candidate ${i + 1}:") })
            parts.put(imagePart(bytes))
        }
        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply { put("parts", parts) }))
            put("generationConfig", JSONObject().apply {
                put("thinkingConfig", JSONObject().apply { put("thinkingBudget", 4000) })
            })
        }
    }

    private fun imagePart(bytes: ByteArray) = JSONObject().apply {
        put("inlineData", JSONObject().apply {
            put("mimeType", "image/jpeg")
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        })
    }

    companion object {
        private const val MAX_DIM = 1024
        private const val CANDIDATE_MAX_DIM = 500
        private const val PROMPT = """You are comparing a photo of a physical vinyl record cover (the first image) against numbered candidate album-art images found online (the images that follow, each preceded by its "Candidate N:" label).

Identify which candidates show the same front-cover artwork as the photo: the same photograph/illustration, layout, and title treatment. A later remaster/anniversary/digital reissue that reuses the same front-cover artwork still counts as a match — do not reject it just because it isn't the original vinyl pressing.

Do NOT count as a match: a different photograph or illustration, a different color scheme, a different album entirely (including a tribute/cover-version album by another artist, or a different volume/edition with different content), or a generic "same artist" image that isn't the specific cover shown.

Minor things that do NOT disqualify a match: the photo being blurry, out of focus, taken at an angle, or poorly lit; glare or wear on the physical sleeve; a price sticker or barcode; or a small "remastered"/anniversary badge added on top of the same artwork. Judge the underlying artwork the photo is showing, not the photo's own image quality.

Return ONLY a JSON object:
{
  "rankedCandidates": [<candidate numbers, best match first, every candidate number listed exactly once>],
  "bestIsGoodMatch": true or false
}
"bestIsGoodMatch" should be true whenever the top-ranked candidate's front-cover artwork clearly matches the photo by the rule above. Only mark it false if none of the candidates are a confident match."""
    }
}

/**
 * Parses Gemini's ranking response into 0-based indices. Defensive against a
 * response that omits candidates, repeats one, or names one out of range —
 * any candidate the model didn't (validly) rank is appended in its original
 * order rather than silently dropped.
 */
internal fun parseCoverMatchResponse(json: String, candidateCount: Int): CoverMatchResult {
    if (candidateCount == 0) return CoverMatchResult(emptyList(), bestIsGoodMatch = false)
    return try {
        val cleaned = json.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val result = JSONObject(cleaned)
        val rankedRaw = result.optJSONArray("rankedCandidates") ?: JSONArray()

        val seen = LinkedHashSet<Int>()
        for (i in 0 until rankedRaw.length()) {
            val n = rankedRaw.optInt(i, -1) - 1 // 1-based -> 0-based
            if (n in 0 until candidateCount) seen.add(n)
        }
        val remaining = (0 until candidateCount).filter { it !in seen }
        CoverMatchResult(seen.toList() + remaining, result.optBoolean("bestIsGoodMatch", false))
    } catch (e: Exception) {
        CoverMatchResult((0 until candidateCount).toList(), bestIsGoodMatch = false)
    }
}
