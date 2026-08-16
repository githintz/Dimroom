package com.dimroom.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dimroom.domain.model.PhotoKind

@Entity(tableName = "photos", indices = [Index("stackId")])
data class PhotoEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** Provider-relative path, e.g. `Originals/All Photos/{id}.jpg`. */
    val originalPath: String,
    val thumbnailPath: String?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAddedMs: Long,
    val dateTakenMs: Long,
    /** How this photo came to exist: imported as-is, or produced by an HDR merge. */
    val kind: PhotoKind = PhotoKind.ORIGINAL,
    /**
     * Groups a merged result with the brackets it came from. Null for a standalone photo.
     * Exactly one member of a stack has [isStackPrimary] set, and only that one appears in the grid.
     */
    val stackId: String? = null,
    val isStackPrimary: Boolean = false,
)

/** Provenance for a merged photo. Grouping is library metadata, like albums — it never leaves Room. */
@Entity(
    tableName = "hdr_merges",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["mergedPhotoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HdrMergeEntity(
    @PrimaryKey val mergedPhotoId: String,
    /** Comma-separated source photo ids, oldest selection order first. */
    val sourcePhotoIds: String,
    val createdAtMs: Long,
    val aligned: Boolean,
)

@Entity(tableName = "albums", indices = [Index(value = ["name"], unique = true)])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
)

@Entity(
    tableName = "album_photos",
    primaryKeys = ["albumId", "photoId"],
    indices = [Index("photoId"), Index("albumId")],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AlbumPhotoCrossRef(
    val albumId: String,
    val photoId: String,
    val addedAtMs: Long,
)

/**
 * The current edit stack for a photo, mirrored to `/Edits/{photoId}.json` by the storage provider.
 * Room is the fast read path; the sidecar is the portable source of truth.
 */
@Entity(
    tableName = "edit_states",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EditStateEntity(
    @PrimaryKey val photoId: String,
    val editJson: String,
    val updatedAtMs: Long,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val editJson: String,
    val isBuiltIn: Boolean,
    val createdAtMs: Long,
)
