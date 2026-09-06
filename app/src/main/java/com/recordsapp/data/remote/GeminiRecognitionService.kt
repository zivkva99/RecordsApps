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
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(uri: Uri): RecognitionResult = withContext(Dispatchers.IO) {
        val imageBytes = compressImage(uri)
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val mimeType = "image/jpeg"

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", PROMPT) })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", mimeType)
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", 8000)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RecognitionApiException(resp.code, body)
            if (body.isEmpty()) throw IllegalStateException("Empty response")
            parseGeminiResponse(body)
        }
    }

    private fun compressImage(uri: Uri): ByteArray =
        ImageCompression.fromUri(context, uri, MAX_DIM, quality = 85)

    companion object {
        private const val MAX_DIM = 1024
        private const val PROMPT = """You are a music expert and vinyl record collector with encyclopedic knowledge of album covers, artwork, and discography. Your knowledge includes Discogs, AllMusic, Wikipedia, Spotify, and music databases worldwide.

Your task: identify the vinyl record in this photo.

READING TEXT ON THE COVER
Read all visible text. Filter out non-title elements: label logos, catalog numbers, "stereo/mono", copyright notices, price stickers, and corner budget series labels (e.g. CBS Israel "25/25", French "Impact", "Hallmark") are never the album title.
On many covers the band/artist name and the album title appear on separate lines — read them as distinct fields; never confuse one for the other.
Read all text completely and exactly — do not truncate, alter, or replace any word.
When reading names in any script, transcribe each word exactly. In Hebrew: ס (samech) and מ (mem) are completely different letters — "סשה" is "Sasha" (S), NOT "Moshe" (which starts with מ). Do not substitute a more famous artist who shares only the surname.
If only the band/artist name is visible with no separate album title, this may be a self-titled album — consider that possibility, especially for bands active in the late 1960s whose debut album shares the band name.

IDENTIFYING THE ALBUM
If prominent text gives you a hypothesis, verify by recalling the actual cover art of that specific album and comparing it to what you see. If the artwork does not match, discard the hypothesis. Then scan the artist COMPLETE discography — including debut albums and early 1960s/1970s works — and match the artwork against each known cover.
If no useful text is visible, identify purely from the visual artwork. As a last resort, use your broadest knowledge as if doing a reverse image search.
If multiple song titles are listed on the front cover, this is a compilation — use "low" confidence.

YEAR — CRITICAL
Use ONLY the original first commercial release year from Discogs or AllMusic. After identifying the album, ask yourself: "When was this specific album (not a live version, not a compilation, not a reissue) FIRST released?" Report that year precisely. Self-titled debut albums from the late 1960s may be from 1968–1970 even if the band is better known for later work.

Return ONLY a JSON object:
{
  "artistName": "...",
  "albumName": "...",
  "year": "...",
  "numRecords": "...",
  "confidence": "high" or "low"
}
Rules: "year" = 4-digit original first release; "numRecords" = disc count; "confidence" = "high" only if certain of ALL fields; empty string if unknown."""
    }
}

class RecognitionApiException(val code: Int, val body: String = "") : Exception("Recognition API error: $code")

class RecognitionParseException(cause: Throwable) : Exception("Failed to parse recognition response", cause)

internal fun parseGeminiResponse(json: String): RecognitionResult {
    try {
        val parts = JSONObject(json)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        // Thinking mode returns a "thought" part first — find the last non-thought text part
        var text = ""
        for (i in parts.length() - 1 downTo 0) {
            val part = parts.getJSONObject(i)
            if (!part.optBoolean("thought", false) && part.has("text")) {
                text = part.getString("text")
                break
            }
        }

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
    } catch (e: Exception) {
        throw RecognitionParseException(e)
    }
}
