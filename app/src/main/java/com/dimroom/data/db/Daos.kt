package com.dimroom.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A photo row joined with whether it carries a non-empty edit stack. */
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
     * The whole library. Sorting is applied in the repository rather than in SQL so that a single
     * query serves every sort order and Room's invalidation stays simple.
     */
    @Query(
        """
        SELECT p.id, p.displayName, p.originalPath, p.thumbnailPath, p.width, p.height,
               p.sizeBytes, p.dateAddedMs, p.dateTakenMs,
               (e.photoId IS NOT NULL) AS hasEdits
        FROM photos p
        LEFT JOIN edit_states e ON e.photoId = p.id
        """,
    )
    fun observeAll(): Flow<List<PhotoWithEditFlag>>

    @Query(
        """
        SELECT p.id, p.displayName, p.originalPath, p.thumbnailPath, p.width, p.height,
               p.sizeBytes, p.dateAddedMs, p.dateTakenMs,
               (e.photoId IS NOT NULL) AS hasEdits
        FROM photos p
        INNER JOIN album_photos ap ON ap.photoId = p.id
        LEFT JOIN edit_states e ON e.photoId = p.id
        WHERE ap.albumId = :albumId
        """,
    )
    fun observeInAlbum(albumId: String): Flow<List<PhotoWithEditFlag>>

    @Query("SELECT albumId FROM album_photos WHERE photoId = :photoId")
    suspend fun albumIdsFor(photoId: String): List<String>
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

    @Query(
        """
        SELECT a.id, a.name, a.createdAtMs,
               COUNT(ap.photoId) AS photoCount,
               (SELECT p.thumbnailPath FROM album_photos ap2
                    INNER JOIN photos p ON p.id = ap2.photoId
                    WHERE ap2.albumId = a.id
                    ORDER BY ap2.addedAtMs DESC LIMIT 1) AS coverPhotoPath
        FROM albums a
        LEFT JOIN album_photos ap ON ap.albumId = a.id
        GROUP BY a.id
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
