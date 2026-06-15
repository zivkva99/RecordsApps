package com.recordsapp.data.drive

data class BackupCopy(
    val gradeSide1: String,
    val gradeSide2: String,
    val country: String,
    val listened: Boolean
)

data class BackupAlbum(
    val artist: String,
    val albumName: String,
    val numRecords: Int,
    val year: Int,
    val comment: String,
    val coverImageFile: String?,
    val copies: List<BackupCopy>
)
