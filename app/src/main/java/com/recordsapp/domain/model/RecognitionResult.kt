package com.recordsapp.domain.model

data class RecognitionResult(
    val artistName: String,
    val albumName: String,
    val year: String,
    val numRecords: String,
    val confidence: Confidence
)

enum class Confidence { HIGH, LOW }
