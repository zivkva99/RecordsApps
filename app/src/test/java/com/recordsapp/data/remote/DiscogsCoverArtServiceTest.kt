package com.recordsapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscogsCoverArtServiceTest {

    @Test
    fun `dedupeByMaster keeps first hit per master group`() {
        val hits = listOf(
            DiscogsSearchHit(id = 1, masterId = 100),
            DiscogsSearchHit(id = 2, masterId = 100), // same master as id 1 -- dropped
            DiscogsSearchHit(id = 3, masterId = 200),
        )

        val result = dedupeByMaster(hits, limit = 6)

        assertEquals(listOf(1L, 3L), result)
    }

    @Test
    fun `dedupeByMaster falls back to release id when master is null`() {
        val hits = listOf(
            DiscogsSearchHit(id = 1, masterId = null),
            DiscogsSearchHit(id = 2, masterId = null),
        )

        val result = dedupeByMaster(hits, limit = 6)

        assertEquals(listOf(1L, 2L), result)
    }

    @Test
    fun `dedupeByMaster caps to the limit`() {
        val hits = (1..10L).map { DiscogsSearchHit(id = it, masterId = it * 1000) }

        val result = dedupeByMaster(hits, limit = 3)

        assertEquals(3, result.size)
    }

    @Test
    fun `pickPrimaryImageUrl prefers the primary-typed image`() {
        val images = listOf("secondary" to "url-secondary", "primary" to "url-primary")

        assertEquals("url-primary", pickPrimaryImageUrl(images))
    }

    @Test
    fun `pickPrimaryImageUrl falls back to the first image when none are primary`() {
        val images = listOf("secondary" to "url-secondary", "secondary" to "url-other")

        assertEquals("url-secondary", pickPrimaryImageUrl(images))
    }

    @Test
    fun `pickPrimaryImageUrl returns null for no images`() {
        assertNull(pickPrimaryImageUrl(emptyList()))
    }
}
