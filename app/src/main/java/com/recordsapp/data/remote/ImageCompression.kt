package com.recordsapp.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Shared downscale/re-encode helpers used before sending images to Gemini.
 * Keeps every caller's outbound payload small regardless of the source
 * photo's resolution.
 */
internal object ImageCompression {

    fun fromUri(context: Context, uri: Uri, maxDim: Int, quality: Int): ByteArray {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot open URI: $uri")
        return fromBytes(raw, maxDim, quality)
    }

    fun fromBytes(raw: ByteArray, maxDim: Int, quality: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IllegalStateException("Cannot decode image")
        val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
