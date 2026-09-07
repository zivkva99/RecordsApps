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

/**
 * Finds cover art candidates for a record via the iTunes Search API.
 *
 * [fetchUrls] combines two strategies because iTunes's free-text relevance
 * ranking is unreliable for well-known albums — confirmed for e.g. Pink
 * Floyd's "The Dark Side of the Moon", which doesn't appear even in the top
 * 25 results for a plain "artist album" text search. Resolving the artist
 * first and fuzzy-matching the target title against their actual catalog
 * finds it reliably instead.
 */
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

    /**
     * Union of a plain text search and an artist-catalog fuzzy match,
     * deduped with catalog matches first (they're the more trustworthy
     * source), capped to [limit].
     */
    suspend fun fetchUrls(artist: String, album: String, limit: Int = 10): List<String> = withContext(Dispatchers.IO) {
        val catalogUrls = fetchCatalogMatches(artist, album)
        val textUrls = fetchTextSearch(artist, album, limit = 6)
        unionCandidates(catalogUrls, textUrls, limit)
    }

    private suspend fun fetchTextSearch(artist: String, album: String, limit: Int): List<String> =
        try {
            val query = URLEncoder.encode("$artist $album", "UTF-8")
            val searchReq = Request.Builder()
                .url("https://itunes.apple.com/search?term=$query&media=music&entity=album&limit=$limit")
                .build()
            client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parseArtworkUrls(body)
            }
        } catch (e: Exception) {
            emptyList()
        }

    private suspend fun fetchCatalogMatches(artist: String, album: String, limit: Int = 6): List<String> =
        try {
            val artistId = resolveArtistId(artist) ?: return emptyList()
            val lookupReq = Request.Builder()
                .url("https://itunes.apple.com/lookup?id=$artistId&entity=album&limit=200")
                .build()
            val catalog = client.newCall(lookupReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parseCatalogEntries(body)
            }
            fuzzyMatchCatalog(album, catalog, cutoff = CATALOG_MATCH_CUTOFF, limit = limit)
        } catch (e: Exception) {
            emptyList()
        }

    private suspend fun resolveArtistId(artist: String): Long? = try {
        val query = URLEncoder.encode(artist, "UTF-8")
        val req = Request.Builder()
            .url("https://itunes.apple.com/search?term=$query&media=music&entity=musicArtist&limit=1")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            results.getJSONObject(0).optLong("artistId").takeIf { it != 0L }
        }
    } catch (e: Exception) {
        null
    }

    private fun parseCatalogEntries(json: String): List<Pair<String, String>> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val entries = mutableListOf<Pair<String, String>>()
        for (i in 0 until results.length()) {
            val entry = results.getJSONObject(i)
            if (entry.optString("wrapperType") != "collection") continue
            val name = entry.optString("collectionName")
            val raw = entry.optString("artworkUrl100")
            if (name.isBlank() || raw.isBlank()) continue
            entries.add(name to raw.replace("100x100bb", "600x600bb"))
        }
        return entries
    }

    private fun parseArtworkUrl(json: String): String? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val url = results.getJSONObject(0).optString("artworkUrl100").ifBlank { return null }
        return url.replace("100x100bb", "600x600bb")
    }

    private fun parseArtworkUrls(json: String): List<String> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (i in 0 until results.length()) {
            val raw = results.getJSONObject(i).optString("artworkUrl100")
            if (raw.isBlank()) continue
            urls.add(raw.replace("100x100bb", "600x600bb"))
        }
        return urls
    }

    companion object {
        private const val CATALOG_MATCH_CUTOFF = 0.5
    }
}

/** Casefolds, strips parenthetical/bracketed reissue suffixes and punctuation. */
internal fun normalizeAlbumTitle(title: String): String {
    var s = title.lowercase().trim()
    s = Regex("[(\\[][^)\\]]*[)\\]]").replace(s, " ")
    s = Regex("[^\\p{L}\\p{N}\\s]", RegexOption.UNIX_LINES).replace(s, " ")
    return Regex("\\s+").replace(s, " ").trim()
}

/** Normalized Levenshtein similarity in [0, 1]; 1.0 means equal after normalization. */
internal fun titleSimilarity(a: String, b: String): Double {
    val na = normalizeAlbumTitle(a)
    val nb = normalizeAlbumTitle(b)
    if (na.isEmpty() && nb.isEmpty()) return 1.0
    val maxLen = maxOf(na.length, nb.length)
    if (maxLen == 0) return 1.0
    return 1.0 - levenshteinDistance(na, nb).toDouble() / maxLen
}

private fun levenshteinDistance(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) {
                dp[i - 1][j - 1]
            } else {
                1 + minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1])
            }
        }
    }
    return dp[a.length][b.length]
}

/**
 * Fuzzy-matches [target] against an artist's (title, url) catalog, keeping
 * only entries at or above [cutoff], best match first, capped to [limit].
 */
internal fun fuzzyMatchCatalog(
    target: String,
    catalog: List<Pair<String, String>>,
    cutoff: Double,
    limit: Int
): List<String> = catalog
    .map { (name, url) -> titleSimilarity(target, name) to url }
    .filter { it.first >= cutoff }
    .sortedByDescending { it.first }
    .take(limit)
    .map { it.second }

/** Dedupes [first] then [second], in that priority order, capped to [limit]. */
internal fun unionCandidates(first: List<String>, second: List<String>, limit: Int): List<String> {
    val seen = LinkedHashSet<String>()
    for (url in first) seen.add(url)
    for (url in second) seen.add(url)
    return seen.take(limit)
}
