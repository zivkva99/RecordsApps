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
Read all text completely and exactly — do not truncate, alter, or replace any word, and do not respell a name phonetically. If you recognize the artist, use their standard, canonical name spelling (in whichever script applies — see below), not an approximation of what the stylized cover lettering looks like.
When reading names in any script, transcribe each word exactly. In Hebrew: ס (samech) and מ (mem) are completely different letters — "סשה" is "Sasha" (S), NOT "Moshe" (which starts with מ). Do not substitute a more famous artist who shares only the surname.
If only the band/artist name is visible with no separate album title, this may be a self-titled album — consider that possibility, especially for bands active in the late 1960s whose debut album shares the band name.

HEBREW RECORDS — SPECIAL CASE
If the cover's text is Hebrew, or the artist is an Israeli / Hebrew-language musician (even one you recognize by a familiar transliterated name, e.g. "Chava Alberstein", "Arik Einstein", "Matti Caspi"), you MUST output BOTH artistName and albumName in Hebrew script (עברית) — never a Latin transliteration. This applies even if the artist is world-famous under an English spelling in your training data: use the standard Hebrew spelling as it would appear on the cover, not the transliteration and not a phonetic respelling. Only fall back to Latin script if the cover text itself is genuinely printed in Latin letters and the artist is not Hebrew-language.

Do NOT apply this rule to non-Hebrew artists. A Western rock/pop act (e.g. Genesis, Bread, The Beatles) stays in Latin script even when you are unsure of the exact album, even if this specific pressing is an Israeli import with a Hebrew price sticker or a Hebrew label logo somewhere on the cover — a sticker or logo is not the album's own text. Never invent a Hebrew transliteration of an English band or album name (e.g. never write "Genesis" as "ג'נסיס") — being unsure which specific album this is is never a reason to switch scripts.

Write Hebrew as plain unpointed text (no niqqud / vowel-point diacritics) — the way titles are normally printed, not the way they'd appear in a prayer book or dictionary.

ONE TITLE, ONE SCRIPT — NO DUPLICATES
Many covers print the same title twice: once in Hebrew, once as an English gloss or transliteration in parentheses or on a second line. Output ONLY the primary title, in the single script chosen above — never append a translation or transliteration in parentheses, and never join two titles with "/". If a cover genuinely combines two distinct album titles (a two-record reissue of two different LPs), pick the one presented as primary/larger, not both joined together.

IDENTIFYING THE ALBUM — DO NOT GUESS A PLAUSIBLE TITLE
Getting the artist right but the specific album wrong is the most common mistake — treat it as seriously as getting the artist wrong.
If prominent text gives you a hypothesis, verify by recalling the actual cover art of that specific album and comparing it to what you see. If the artwork does not match, discard the hypothesis.
Then scan the artist's COMPLETE discography — including debut albums and early 1960s/1970s works — and mentally compare the photographed artwork (background color, pose, objects, photo vs. illustration) against at least three candidate albums by that artist before finalizing, not just the first or most famous one that comes to mind. Artists with many self-titled, numbered, or similar portrait-style covers are the most common source of a wrong-album-right-artist mistake.
Do not default to a self-titled or "greatest hits" guess merely because you recognize the artist but can't place the specific record — that is exactly the situation that calls for "low" confidence, not a confident guess. Only report a specific album as certain when you can recall a concrete, matching visual detail of THAT album's actual cover (not just the artist's general visual style), or when the title is legible on the cover itself.
Never output an approximate or reconstructed-sounding title — every word of albumName must be either read directly from the cover or be the exact, verbatim title of a real release you specifically recall, spelled the way it is actually spelled. If you are reconstructing a title from a vague memory of "something like that", stop and either read the cover text literally instead, or use "low" confidence with your best literal reading.
If no useful text is visible, identify purely from the visual artwork. As a last resort, use your broadest knowledge as if doing a reverse image search.
If multiple song titles are listed on the front cover, this is a compilation — use "low" confidence.

CONFIDENCE — BE HONEST WHEN GUESSING
Mark "high" confidence only if you are certain of the artist AND the exact album AND the year — not merely confident about the artist or the general era.
Recognizing the artist from the cover photo/logo is not the same as knowing which specific album this is — if you had to pick the album from discography knowledge rather than reading it or matching a specific remembered cover, use "low".
If the cover has little or no legible text and you are relying on recalling the artwork alone, use "high" confidence only when at least two independent, specific visual details (e.g. the exact color palette AND a distinctive image/composition detail) match your recollection of that specific album's cover — not just a general vibe or genre match. Otherwise use "low".

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
