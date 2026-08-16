package com.dimroom.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dimroom.domain.model.PhotoKind
import kotlinx.coroutines.flow.Flow

/** A photo row joined with whether it carries a non-empty edit stack and how big its stack is. */
data class PhotoWithEditFlag(
    val id: String,
    val displayName: String,
    val originalPath: String,
    val thumbnailPath: String?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAddedMs: Long,
    val dateTakenMs: Long,
    val hasEdits: Boolean,
    val kind: PhotoKind,
    val stackId: String?,
    /** Total photos in this row's stack, or 0 when it is not stacked. */
    val stackSize: Int,
)

/** An album row with its live photo count and a cover thumbnail. */
data class AlbumWithCount(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val photoCount: Int,
    val coverPhotoPath: String?,
)

@Dao
interface PhotoDao {

    @Upsert
    suspend fun upsert(photo: PhotoEntity)

    @Upsert
    suspend fun upsertAll(photos: List<PhotoEntity>)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun findById(id: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id = :id")
    fun observeById(id: String): Flow<PhotoEntity?>

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM photos")
    fun observeCount(): Flow<Int>

    /**
     * The whole library, one row per grid tile. Members of a stack collapse into their primary, so
     * a merged HDR shows as a single photo with its brackets tucked inside.
     *
     * Sorting is applied in the repository rather than in SQL so that a single query serves every
     * sort order and Room's invalidation stays simple.
     */
    @Query(
        """
        SELECT p.id, p.displayName, p.originalPath, p.thumbnailPath, p.width, p.height,
               p.sizeBytes, p.dateAddedMs, p.dateTakenMs,
               (e.photoId IS NOT NULL) AS hasEdits,
               p.kind AS kind, p.stackId AS stackId,
               (SELECT COUNT(*) FROM photos m WHERE m.stackId = p.stackId) AS stackSize
        FROM photos p
        LEFT JOIN edit_states e ON e.photoId = p.id
        WHERE p.stackId IS NULL OR p.isStackPrimary = 1
        """,
    )
    fun observeAll(): Flow<List<PhotoWithEditFlag>>

    @Query(
        """
        SELECT p.id, p.displayName, p.originalPath, p.thumbnailPath, p.width, p.height,
               p.sizeBytes, p.dateAddedMs, p.dateTakenMs,
               (e.photoId IS NOT NULL) AS hasEdits,
               p.kind AS kind, p.stackId AS stackId,
               (SELECT COUNT(*) FROM photos m WHERE m.stackId = p.stackId) AS stackSize
        FROM photos p
        INNER JOIN album_photos ap ON ap.photoId = p.id
        LEFT JOIN edit_states e ON e.photoId = p.id
        WHERE ap.albumId = :albumId AND (p.stackId IS NULL OR p.isStackPrimary = 1)
        """,
    )
    fun observeInAlbum(albumId: String): Flow<List<PhotoWithEditFlag>>

    @Query("SELECT albumId FROM album_photos WHERE photoId = :photoId")
    suspend fun albumIdsFor(photoId: String): List<String>

    @Query("SELECT DISTINCT albumId FROM album_photos WHERE photoId IN (:photoIds)")
    suspend fun albumIdsForAny(photoIds: List<String>): List<String>

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun findAllById(ids: List<String>): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE stackId = :stackId")
    suspend fun findInStack(stackId: String): List<PhotoEntity>

    /** Joins [photoIds] into [stackId]; [primaryId] becomes the tile that represents the group. */
    @Query(
        """
        UPDATE photos SET stackId = :stackId, isStackPrimary = (id = :primaryId)
        WHERE id IN (:photoIds)
        """,
    )
    suspend fun assignStack(stackId: String, primaryId: String, photoIds: List<String>)

    /** Releases every member of a stack back into the grid as a standalone photo. */
    @Query("UPDATE photos SET stackId = NULL, isStackPrimary = 0 WHERE stackId = :stackId")
    suspend fun dissolveStack(stackId: String)
}

@Dao
interface AlbumDao {

    @Upsert
    suspend fun upsert(album: AlbumEntity)

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun findById(id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): AlbumEntity?

    @Query("UPDATE albums SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Counts and covers ignore stacked brackets, so an album's count matches the tiles on screen. */
    @Query(
        """
        SELECT a.id, a.name, a.createdAtMs,
               (SELECT COUNT(*) FROM album_photos ap
                    INNER JOIN photos p ON p.id = ap.photoId
                    WHERE ap.albumId = a.id
                      AND (p.stackId IS NULL OR p.isStackPrimary = 1)) AS photoCount,
               (SELECT p.thumbnailPath FROM album_photos ap2
                    INNER JOIN photos p ON p.id = ap2.photoId
                    WHERE ap2.albumId = a.id
                      AND (p.stackId IS NULL OR p.isStackPrimary = 1)
                    ORDER BY ap2.addedAtMs DESC LIMIT 1) AS coverPhotoPath
        FROM albums a
        ORDER BY a.createdAtMs DESC
        """,
    )
    fun observeAlbums(): Flow<List<AlbumWithCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPhoto(crossRef: AlbumPhotoCrossRef)

    @Query("DELETE FROM album_photos WHERE albumId = :albumId AND photoId = :photoId")
    suspend fun removePhoto(albumId: String, photoId: String)

    @Transaction
    suspend fun addPhotos(albumId: String, photoIds: List<String>, nowMs: Long) {
        photoIds.forEach { addPhoto(AlbumPhotoCrossRef(albumId, it, nowMs)) }
    }
}

@Dao
interface EditDao {

    @Upsert
    suspend fun upsert(state: EditStateEntity)

    @Query("SELECT * FROM edit_states WHERE photoId = :photoId")
    suspend fun findById(photoId: String): EditStateEntity?

    @Query("SELECT * FROM edit_states WHERE photoId = :photoId")
    fun observeById(photoId: String): Flow<EditStateEntity?>

    @Query("DELETE FROM edit_states WHERE photoId = :photoId")
    suspend fun deleteById(photoId: String)
}

@Dao
interface HdrDao {

    @Upsert
    suspend fun upsert(merge: HdrMergeEntity)

    @Query("SELECT * FROM hdr_merges WHERE mergedPhotoId = :photoId")
    suspend fun findByPhotoId(photoId: String): HdrMergeEntity?
}

@Dao
interface PresetDao {

    @Upsert
    suspend fun upsert(preset: PresetEntity)

    @Upsert
    suspend fun upsertAll(presets: List<PresetEntity>)

    @Query("SELECT * FROM presets ORDER BY isBuiltIn DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun findById(id: String): PresetEntity?

    @Query("DELETE FROM presets WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteUserPreset(id: String)

    @Query("SELECT COUNT(*) FROM presets WHERE isBuiltIn = 1")
    suspend fun builtInCount(): Int
}
