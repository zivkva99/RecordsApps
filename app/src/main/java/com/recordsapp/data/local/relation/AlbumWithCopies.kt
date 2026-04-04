package com.recordsapp.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.recordsapp.data.local.entity.AlbumEntity
import com.recordsapp.data.local.entity.CopyEntity

data class AlbumWithCopies(
    @Embedded val album: AlbumEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "albumId"
    )
    val copies: List<CopyEntity>
)
