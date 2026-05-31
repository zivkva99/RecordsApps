package com.recordsapp.data.remote

import com.recordsapp.data.local.ImageStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ItunesCoverArtService @Inject constructor(
    private val imageStorage: ImageStorage
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchUrl(artist: String, album: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $album", "UTF-8")
            val searchReq = Request.Builder()
                .url("https://itunes.apple.com/search?term=$query&media=music&entity=album&limit=5")
                .build()
            client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                parseArtworkUrl(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchAndSave(artist: String, album: String): String? =
        fetchUrlAndSave(fetchUrl(artist, album))

    suspend fun fetchUrlAndSave(artworkUrl: String?): String? = withContext(Dispatchers.IO) {
        if (artworkUrl == null) return@withContext null
        try {
            val imageReq = Request.Builder().url(artworkUrl).build()
            val bytes = client.newCall(imageReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            } ?: return@withContext null
            imageStorage.saveImageFromBytes(bytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseArtworkUrl(json: String): String? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val url = results.getJSONObject(0).optString("artworkUrl100").ifBlank { return null }
        return url.replace("100x100bb", "600x600bb")
    }
}
