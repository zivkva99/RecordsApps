package com.recordsapp.data.remote

import com.recordsapp.domain.model.Confidence
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiRecognitionServiceTest {

    @Test
    fun `parseGeminiResponse extracts all fields from clean JSON`() {
        val json = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\"artistName\":\"Pink Floyd\",\"albumName\":\"The Wall\",\"year\":\"1979\",\"numRecords\":\"2\",\"confidence\":\"high\"}"
              }]
            }
          }]
        }
        """.trimIndent()

        val result = parseGeminiResponse(json)

        assertEquals("Pink Floyd", result.artistName)
        assertEquals("The Wall", result.albumName)
        assertEquals("1979", result.year)
        assertEquals("2", result.numRecords)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun `parseGeminiResponse strips markdown code fences`() {
        val json = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "```json\n{\"artistName\":\"Led Zeppelin\",\"albumName\":\"IV\",\"year\":\"1971\",\"numRecords\":\"1\",\"confidence\":\"low\"}\n```"
              }]
            }
          }]
        }
        """.trimIndent()

        val result = parseGeminiResponse(json)

        assertEquals("Led Zeppelin", result.artistName)
        assertEquals("IV", result.albumName)
        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun `parseGeminiResponse defaults unknown confidence to LOW`() {
        val json = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\"artistName\":\"\",\"albumName\":\"\",\"year\":\"\",\"numRecords\":\"\",\"confidence\":\"medium\"}"
              }]
            }
          }]
        }
        """.trimIndent()

        val result = parseGeminiResponse(json)

        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun `parseGeminiResponse returns empty strings for missing fields`() {
        val json = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\"confidence\":\"low\"}"
              }]
            }
          }]
        }
        """.trimIndent()

        val result = parseGeminiResponse(json)

        assertEquals("", result.artistName)
        assertEquals("", result.albumName)
        assertEquals("", result.year)
    }
}
