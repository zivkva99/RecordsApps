package com.recordsapp.data.drive

import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.entity.CopyEntity
import com.recordsapp.data.local.relation.AlbumWithCopies
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupSerializerTest {

    private val serializer = BackupSerializer()

    private fun awc(
        artistName: String = "Pink Floyd",
        albumName: String = "The Wall",
        numRecords: Int = 2,
        year: Int = 1979,
        comment: String = "Original pressing",
        coverImagePath: String? = "/data/user/0/com.recordsapp/files/cover_abc.jpg"
    ) = AlbumWithCopies(
        album = AlbumEntity(
            id = 1,
            artistName = artistName,
            albumName = albumName,
            numRecords = numRecords,
            year = year,
            coverImagePath = coverImagePath,
            comment = comment
        ),
        copies = listOf(
            CopyEntity(
                id = 1, albumId = 1,
                gradeSide1 = "Very Good Plus", gradeSide2 = "Very Good",
                country = "UK", listened = true
            )
        )
    )

    @Test
    fun `serialize produces correct album fields`() {
        val json = serializer.serialize(listOf(awc()))
        val album = JSONObject(json).getJSONArray("albums").getJSONObject(0)
        assertEquals("Pink Floyd", album.getString("artist"))
        assertEquals("The Wall", album.getString("albumName"))
        assertEquals(2, album.getInt("numRecords"))
        assertEquals(1979, album.getInt("year"))
        assertEquals("Original pressing", album.getString("comment"))
        assertEquals("cover_abc.jpg", album.getString("coverImageFile"))
    }

    @Test
    fun `serialize produces correct copy fields`() {
        val json = serializer.serialize(listOf(awc()))
        val copy = JSONObject(json).getJSONArray("albums").getJSONObject(0)
            .getJSONArray("copies").getJSONObject(0)
        assertEquals("Very Good Plus", copy.getString("gradeSide1"))
        assertEquals("Very Good", copy.getString("gradeSide2"))
        assertEquals("UK", copy.getString("country"))
        assertTrue(copy.getBoolean("listened"))
    }

    @Test
    fun `serialize sets coverImageFile null when no cover path`() {
        val json = serializer.serialize(listOf(awc(coverImagePath = null)))
        val album = JSONObject(json).getJSONArray("albums").getJSONObject(0)
        assertTrue(album.isNull("coverImageFile"))
    }

    @Test
    fun `serialize includes version 1 and exportedAt`() {
        val json = serializer.serialize(emptyList())
        val root = JSONObject(json)
        assertEquals(1, root.getInt("version"))
        assertTrue(root.getString("exportedAt").isNotEmpty())
    }

    @Test
    fun `deserialize returns correct number of albums`() {
        val json = serializer.serialize(listOf(awc(), awc(artistName = "Led Zeppelin", albumName = "IV")))
        assertEquals(2, serializer.deserialize(json).size)
    }

    @Test
    fun `round-trip preserves all album and copy fields`() {
        val result = serializer.deserialize(serializer.serialize(listOf(awc()))).single()
        assertEquals("Pink Floyd", result.artist)
        assertEquals("The Wall", result.albumName)
        assertEquals(2, result.numRecords)
        assertEquals(1979, result.year)
        assertEquals("Original pressing", result.comment)
        assertEquals("cover_abc.jpg", result.coverImageFile)
        assertEquals(1, result.copies.size)
        assertEquals("Very Good Plus", result.copies[0].gradeSide1)
        assertEquals("UK", result.copies[0].country)
        assertTrue(result.copies[0].listened)
    }

    @Test
    fun `round-trip preserves null coverImageFile`() {
        val result = serializer.deserialize(serializer.serialize(listOf(awc(coverImagePath = null)))).single()
        assertNull(result.coverImageFile)
    }

    @Test
    fun `deserialize handles empty albums array`() {
        val json = """{"version":1,"exportedAt":"2026-06-15T10:00:00Z","albums":[]}"""
        assertTrue(serializer.deserialize(json).isEmpty())
    }
}
