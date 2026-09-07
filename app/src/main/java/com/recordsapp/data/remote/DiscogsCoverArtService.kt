package com.recordsapp.data.remote

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
 * Finds cover art candidates via the Discogs database -- a vinyl-collector
 * marketplace with far deeper coverage of regional/obscure pressings than
 * iTunes (see [ItunesCoverArtService]). Confirmed it carries Israeli
 * Hebrew releases iTunes's catalog doesn't have at all.
 *
 * Discogs's *search* endpoint omits image URLs unless the request is
 * authenticated; the per-release *detail* endpoint returns full images
 * without auth. So: search for release IDs, then fetch detail for a few
 * of them (deduped by master release, since same master release group
 * means essentially the same artwork -- no point paying for a detail
 * fetch on both).
 *
 * Runs two release searches -- combined "artist album" and album-only --
 * and unions them. Hebrew glues conjunctions onto the next word with no
 * space ("X ומיקי Y" = "X and Miki Y"), which silently zeroes out the
 * combined query's results against Discogs's tokenized index in a way a
 * plain text match doesn't reveal; album-only still finds it.
 */
@Singleton
class DiscogsCoverArtService @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchUrls(artist: String, album: String, detailLimit: Int = 4): List<String> =
        withContext(Dispatchers.IO) {
            val hits = searchReleases("$artist $album") + searchReleases(album)
            val releaseIds = dedupeByMaster(hits, detailLimit)
            releaseIds.mapNotNull { fetchPrimaryImage(it) }
        }

    private fun searchReleases(query: String): List<DiscogsSearchHit> = try {
        val q = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("https://api.discogs.com/database/search?q=$q&type=release")
            .header("User-Agent", "RecordsApp/1.0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                val masterId = r.optLong("master_id", 0L).takeIf { it != 0L }
                DiscogsSearchHit(id = r.optLong("id"), masterId = masterId)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun fetchPrimaryImage(releaseId: Long): String? = try {
        val request = Request.Builder()
            .url("https://api.discogs.com/releases/$releaseId")
            .header("User-Agent", "RecordsApp/1.0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val images = JSONObject(body).optJSONArray("images") ?: return null
            val pairs = (0 until images.length()).map { i ->
                val im = images.getJSONObject(i)
                im.optString("type") to im.optString("uri")
            }
            pickPrimaryImageUrl(pairs)
        }
    } catch (e: Exception) {
        null
    }
}

internal data class DiscogsSearchHit(val id: Long, val masterId: Long?)

/**
 * Keeps the first hit per master-release group (falling back to the
 * release id itself when a hit has no master), capped to [limit].
 */
internal fun dedupeByMaster(hits: List<DiscogsSearchHit>, limit: Int): List<Long> {
    val seen = mutableSetOf<Long>()
    val ids = mutableListOf<Long>()
    for (hit in hits) {
        val key = hit.masterId ?: hit.id
        if (!seen.add(key)) continue
        ids.add(hit.id)
        if (ids.size >= limit) break
    }
    return ids
}

/** Prefers the (type, url) pair typed "primary"; falls back to the first entry. */
internal fun pickPrimaryImageUrl(images: List<Pair<String, String>>): String? {
    val primary = images.firstOrNull { it.first == "primary" }
    return (primary ?: images.firstOrNull())?.second?.ifBlank { null }
}
