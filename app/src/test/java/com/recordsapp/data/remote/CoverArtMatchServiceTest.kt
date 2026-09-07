package com.recordsapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtMatchServiceTest {

    @Test
    fun `parseCoverMatchResponse extracts ranked indices from clean JSON`() {
        val json = """
        {
          "rankedCandidates": [2, 1, 3],
          "bestIsGoodMatch": true
        }
        """.trimIndent()

        val result = parseCoverMatchResponse(json, candidateCount = 3)

        assertEquals(listOf(1, 0, 2), result.rankedIndices)
        assertTrue(result.bestIsGoodMatch)
    }

    @Test
    fun `parseCoverMatchResponse strips markdown code fences`() {
        val json = "```json\n{\"rankedCandidates\":[1,2],\"bestIsGoodMatch\":false}\n```"

        val result = parseCoverMatchResponse(json, candidateCount = 2)

        assertEquals(listOf(0, 1), result.rankedIndices)
        assertFalse(result.bestIsGoodMatch)
    }

    @Test
    fun `parseCoverMatchResponse defaults bestIsGoodMatch to false when missing`() {
        val json = """{"rankedCandidates":[1]}"""

        val result = parseCoverMatchResponse(json, candidateCount = 1)

        assertFalse(result.bestIsGoodMatch)
    }

    @Test
    fun `parseCoverMatchResponse appends missing indices not named by the model`() {
        // Model only ranked candidate 2 out of 3 — candidates 1 and 3 must still
        // appear (in their original order) so no candidate silently disappears.
        val json = """{"rankedCandidates":[2],"bestIsGoodMatch":true}"""

        val result = parseCoverMatchResponse(json, candidateCount = 3)

        assertEquals(listOf(1, 0, 2), result.rankedIndices)
    }

    @Test
    fun `parseCoverMatchResponse drops out-of-range and duplicate values`() {
        val json = """{"rankedCandidates":[5, 1, 1, 0, 2],"bestIsGoodMatch":true}"""

        val result = parseCoverMatchResponse(json, candidateCount = 2)

        assertEquals(listOf(0, 1), result.rankedIndices)
    }

    @Test
    fun `parseCoverMatchResponse falls back to identity order on malformed JSON`() {
        val result = parseCoverMatchResponse("not json at all", candidateCount = 3)

        assertEquals(listOf(0, 1, 2), result.rankedIndices)
        assertFalse(result.bestIsGoodMatch)
    }

    @Test
    fun `parseCoverMatchResponse handles zero candidates`() {
        val result = parseCoverMatchResponse("""{"rankedCandidates":[],"bestIsGoodMatch":false}""", candidateCount = 0)

        assertEquals(emptyList<Int>(), result.rankedIndices)
    }
}
