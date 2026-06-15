package com.recordsapp.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class RestoreResult(
    val json: String,
    val coverImages: Map<String, ByteArray>
)

@Singleton
class DriveBackupRepository @Inject constructor() {

    private val client = OkHttpClient()

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FOLDER_NAME = "RecordsApp"
    }

    private suspend fun findOrCreateFolder(token: String): String = withContext(Dispatchers.IO) {
        val query = "name='$FOLDER_NAME' and mimeType='$FOLDER_MIME' and 'root' in parents and trashed=false"
        val url = "$BASE/files?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id)"
        val searchResponse = client.newCall(
            Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
        ).execute()
        val files = JSONObject(searchResponse.body!!.string()).getJSONArray("files")
        if (files.length() > 0) return@withContext files.getJSONObject(0).getString("id")

        val metadata = """{"name":"$FOLDER_NAME","mimeType":"$FOLDER_MIME","parents":["root"]}"""
        val createResponse = client.newCall(
            Request.Builder()
                .url("$BASE/files")
                .addHeader("Authorization", "Bearer $token")
                .post(metadata.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        JSONObject(createResponse.body!!.string()).getString("id")
    }

    private suspend fun listFiles(token: String, folderId: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val query = "'$folderId' in parents and trashed=false"
            val url = "$BASE/files?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)"
            val response = client.newCall(
                Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
            ).execute()
            val arr = JSONObject(response.body!!.string()).getJSONArray("files")
            (0 until arr.length()).associate { i ->
                val obj = arr.getJSONObject(i)
                obj.getString("name") to obj.getString("id")
            }
        }

    private suspend fun deleteFile(token: String, fileId: String) = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url("$BASE/files/$fileId")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
        ).execute()
    }

    private suspend fun uploadFile(
        token: String,
        folderId: String,
        name: String,
        bytes: ByteArray,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        val metadata = """{"name":"$name","parents":["$folderId"]}"""
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                metadata.toRequestBody()
            )
            .addPart(
                Headers.headersOf("Content-Type", mimeType),
                bytes.toRequestBody()
            )
            .build()
        client.newCall(
            Request.Builder()
                .url("$UPLOAD_BASE/files?uploadType=multipart")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
        ).execute()
    }

    private suspend fun downloadFile(token: String, fileId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val response = client.newCall(
                Request.Builder()
                    .url("$BASE/files/$fileId?alt=media")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            ).execute()
            response.body!!.bytes()
        }

    suspend fun backup(
        token: String,
        json: String,
        coverImages: Map<String, ByteArray>,
        onProgress: (String) -> Unit
    ) {
        onProgress("Connecting to Google Drive…")
        val folderId = findOrCreateFolder(token)
        val existing = listFiles(token, folderId)

        onProgress("Uploading backup data…")
        existing["backup.json"]?.let { deleteFile(token, it) }
        uploadFile(token, folderId, "backup.json", json.toByteArray(), "application/json")

        coverImages.entries.forEachIndexed { index, (name, bytes) ->
            onProgress("Uploading cover art ${index + 1} / ${coverImages.size}…")
            existing[name]?.let { deleteFile(token, it) }
            uploadFile(token, folderId, name, bytes, "image/jpeg")
        }
    }

    suspend fun restore(
        token: String,
        onProgress: (String) -> Unit
    ): RestoreResult {
        onProgress("Connecting to Google Drive…")
        val folderId = findOrCreateFolder(token)
        val files = listFiles(token, folderId)

        val jsonFileId = files["backup.json"] ?: error("No backup found on Google Drive")
        onProgress("Downloading backup data…")
        val json = downloadFile(token, jsonFileId).toString(Charsets.UTF_8)

        val imageFiles = files.filter { it.key != "backup.json" }
        val coverImages = mutableMapOf<String, ByteArray>()
        imageFiles.entries.forEachIndexed { index, (name, id) ->
            onProgress("Downloading cover art ${index + 1} / ${imageFiles.size}…")
            coverImages[name] = downloadFile(token, id)
        }

        return RestoreResult(json = json, coverImages = coverImages)
    }
}
