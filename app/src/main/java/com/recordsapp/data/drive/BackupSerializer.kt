package com.recordsapp.data.drive

import com.recordsapp.data.local.relation.AlbumWithCopies
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupSerializer @Inject constructor() {

    fun serialize(albums: List<AlbumWithCopies>): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", Instant.now().toString())
        val albumsArray = JSONArray()
        albums.forEach { awc ->
            val albumObj = JSONObject()
            albumObj.put("artist", awc.album.artistName)
            albumObj.put("albumName", awc.album.albumName)
            albumObj.put("numRecords", awc.album.numRecords)
            albumObj.put("year", awc.album.year)
            albumObj.put("comment", awc.album.comment)
            albumObj.put("coverImageFile", awc.album.coverImagePath?.let { File(it).name })
            val copiesArray = JSONArray()
            awc.copies.forEach { copy ->
                val copyObj = JSONObject()
                copyObj.put("gradeSide1", copy.gradeSide1)
                copyObj.put("gradeSide2", copy.gradeSide2)
                copyObj.put("country", copy.country)
                copyObj.put("listened", copy.listened)
                copiesArray.put(copyObj)
            }
            albumObj.put("copies", copiesArray)
            albumsArray.put(albumObj)
        }
        root.put("albums", albumsArray)
        return root.toString()
    }

    fun deserialize(json: String): List<BackupAlbum> {
        val albumsArray = JSONObject(json).getJSONArray("albums")
        return (0 until albumsArray.length()).map { i ->
            val obj = albumsArray.getJSONObject(i)
            val copiesArray = obj.getJSONArray("copies")
            BackupAlbum(
                artist = obj.getString("artist"),
                albumName = obj.getString("albumName"),
                numRecords = obj.getInt("numRecords"),
                year = obj.getInt("year"),
                comment = obj.optString("comment", ""),
                coverImageFile = if (obj.has("coverImageFile") && !obj.isNull("coverImageFile"))
                    obj.getString("coverImageFile") else null,
                copies = (0 until copiesArray.length()).map { j ->
                    val c = copiesArray.getJSONObject(j)
                    BackupCopy(
                        gradeSide1 = c.getString("gradeSide1"),
                        gradeSide2 = c.getString("gradeSide2"),
                        country = c.getString("country"),
                        listened = c.getBoolean("listened")
                    )
                }
            )
        }
    }
}
