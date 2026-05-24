package com.recordsapp.data.local

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun saveImageFromUri(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open URI: $uri")
        val fileName = "cover_${UUID.randomUUID()}.jpg"
        val file = File(context.filesDir, fileName)
        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }

    fun saveImageFromBytes(bytes: ByteArray): String {
        val file = File(context.filesDir, "cover_${UUID.randomUUID()}.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun deleteImage(path: String) {
        File(path).delete()
    }
}
