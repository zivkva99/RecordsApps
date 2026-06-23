package com.recordsapp.data.local

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QaExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val examplesDir: File
        get() = File(context.getExternalFilesDir(null), "examples").also { it.mkdirs() }

    private val datasetFile: File
        get() = File(examplesDir, "qa_dataset.json")

    suspend fun export(
        uri: Uri,
        artistName: String,
        albumName: String,
        year: String,
        numRecords: String
    ) = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "cover_$timestamp.jpg"

        val destFile = File(examplesDir, filename)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        val array = if (datasetFile.exists()) JSONArray(datasetFile.readText()) else JSONArray()
        array.put(JSONObject().apply {
            put("filename", filename)
            put("artistName", artistName)
            put("albumName", albumName)
            put("year", year)
            put("numRecords", numRecords)
            put("addedAt", timestamp)
        })
        datasetFile.writeText(array.toString(2))
    }
}
