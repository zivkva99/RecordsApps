package com.recordsapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesCoverArtServiceTest {

    @Test
    fun `normalizeAlbumTitle casefolds and collapses whitespace`() {
        assertEquals("selling england by the pound", normalizeAlbumTitle("  Selling England By The Pound  "))
    }

    @Test
    fun `normalizeAlbumTitle strips parenthetical and bracketed reissue suffixes`() {
        assertEquals(
            "the dark side of the moon",
            normalizeAlbumTitle("The Dark Side of the Moon (50th Anniversary) [Remastered]")
        )
    }

    @Test
    fun `normalizeAlbumTitle strips punctuation`() {
        assertEquals("greatest hits vol ii", normalizeAlbumTitle("Greatest Hits, Vol. II"))
    }

    @Test
    fun `titleSimilarity is 1 for titles equal after normalization`() {
        assertEquals(1.0, titleSimilarity("Genesis", "Genesis "), 0.0001)
        assertEquals(
            1.0,
            titleSimilarity("Selling England By The Pound", "selling england by the pound"),
            0.0001
        )
    }

    @Test
    fun `titleSimilarity is high for a reissue suffix but not identical`() {
        val ratio = titleSimilarity(
            "The Dark Side of the Moon",
            "The Dark Side of the Moon (50th Anniversary) [Remastered]"
        )
        assertTrue("expected a high similarity, got $ratio", ratio > 0.6)
    }

    @Test
    fun `titleSimilarity is low for unrelated titles`() {
        val ratio = titleSimilarity("Foxtrot", "A Trick of the Tail")
        assertTrue("expected a low similarity, got $ratio", ratio < 0.4)
    }

    @Test
    fun `fuzzyMatchCatalog keeps only entries at or above the cutoff`() {
        val catalog = listOf(
            "The Dark Side of the Moon" to "url-real",
            "The Wall" to "url-wall",
            "A Momentary Lapse of Reason" to "url-other"
        )

        val result = fuzzyMatchCatalog("Dark Side of the Moon", catalog, cutoff = 0.5, limit = 6)

        assertEquals(listOf("url-real"), result)
    }

    @Test
    fun `fuzzyMatchCatalog sorts best match first`() {
        val catalog = listOf(
            "Greatest Hits" to "url-vol1",
            "Greatest Hits, Vol. II" to "url-vol2"
        )

        val result = fuzzyMatchCatalog("Greatest Hits, Vol. II", catalog, cutoff = 0.3, limit = 6)

        assertEquals("url-vol2", result.first())
    }

    @Test
    fun `fuzzyMatchCatalog caps results to the limit`() {
        val catalog = (1..10).map { "Live Album $it" to "url-$it" }

        val result = fuzzyMatchCatalog("Live Album", catalog, cutoff = 0.0, limit = 3)

        assertEquals(3, result.size)
    }

    @Test
    fun `fuzzyMatchCatalog returns empty list for empty catalog`() {
        assertEquals(emptyList<String>(), fuzzyMatchCatalog("Anything", emptyList(), cutoff = 0.4, limit = 6))
    }

    @Test
    fun `unionCandidates dedupes while preferring catalog matches first`() {
        val catalog = listOf("a", "b")
        val text = listOf("b", "c", "d")

        val result = unionCandidates(catalog, text, limit = 4)

        assertEquals(listOf("a", "b", "c", "d"), result)
    }

    @Test
    fun `unionCandidates caps to the limit`() {
        val result = unionCandidates(listOf("a", "b"), listOf("c", "d", "e"), limit = 3)

        assertEquals(listOf("a", "b", "c"), result)
    }
}
