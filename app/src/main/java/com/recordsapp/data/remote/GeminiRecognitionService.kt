package com.recordsapp.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    private fun compressImage(uri: Uri): ByteArray {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot open URI: $uri")
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IllegalStateException("Cannot decode image")
        val scale = minOf(MAX_DIM.toFloat() / bitmap.width, MAX_DIM.toFloat() / bitmap.height, 1f)
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        else bitmap
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    companion object {
        private const val MAX_DIM = 1024
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

class RecognitionApiException(val code: Int, val body: String = "") : Exception("Recognition API error: $code")

class RecognitionParseException(cause: Throwable) : Exception("Failed to parse recognition response", cause)

internal fun parseGeminiResponse(json: String): RecognitionResult {
    try {
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
    } catch (e: Exception) {
        throw RecognitionParseException(e)
    }
}
