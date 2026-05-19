package com.recordsapp.data.remote

import android.net.Uri
import com.recordsapp.domain.model.RecognitionResult

interface RecognitionService {
    suspend fun recognize(uri: Uri): RecognitionResult
}
