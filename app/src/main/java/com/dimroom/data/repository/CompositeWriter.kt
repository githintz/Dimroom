package com.dimroom.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.dimroom.data.db.AlbumDao
import com.dimroom.data.db.AlbumPhotoCrossRef
import com.dimroom.data.db.CompositeDao
import com.dimroom.data.db.CompositeEntity
import com.dimroom.data.db.PhotoDao
import com.dimroom.data.db.PhotoEntity
import com.dimroom.data.storage.StorageProvider
import com.dimroom.data.storage.StorageResult
import com.dimroom.data.storage.sanitizedAsFolder
import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.MergeMode
import com.dimroom.domain.model.PhotoKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a photo built from several others into the library.
 *
 * HDR and panorama differ entirely in how they produce their pixels and not at all in what happens
 * afterwards — same storage path, same thumbnail, same album inheritance, same optional stacking. So
 * that half lives here once, and each feature owns only its own image processing.
 *
 * The result is an ordinary library photo: real bytes under `/Originals`, its own thumbnail, and a
 * fresh editable edit stack. Nothing downstream special-cases it, so editing, presets, export and
 * albums all work on a composite exactly as they do on an import.
 */
@Singleton
class CompositeWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageProvider,
    private val photoRepository: PhotoRepository,
    private val photoDao: PhotoDao,
    private val albumDao: AlbumDao,
    private val compositeDao: CompositeDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Photos the caller wants to combine, resolved to entities and files in the requested order. */
    class Sources(val entities: List<PhotoEntity>, val files: List<File>)

    /**
     * Resolves source ids to entities and files, preserving the caller's order.
     *
     * Order matters to both features — it is the alignment reference for HDR and the sweep order for
     * a panorama — and `findAllById` does not preserve it.
     */
    suspend fun resolveSources(photoIds: List<String>): Sources? = withContext(ioDispatcher) {
        val entities = photoDao.findAllById(photoIds).sortedBy { photoIds.indexOf(it.id) }
        if (entities.size != photoIds.size) return@withContext null
        val files = entities.mapNotNull { photoRepository.localFileFor(it.originalPath) }
        if (files.size != entities.size) return@withContext null
        Sources(entities, files)
    }

    /**
     * Writes [bitmap] into the library as a new photo derived from [sources].
     *
     * Returns the stored entity, or null when the bytes could not be written.
     */
    suspend fun write(
        bitmap: Bitmap,
        sources: Sources,
        name: String,
        fallbackName: String,
        kind: PhotoKind,
        mode: MergeMode,
        aligned: Boolean,
    ): PhotoEntity? = withContext(ioDispatcher) {
        val photoId = UUID.randomUUID().toString()
        val sourceIds = sources.entities.map { it.id }
        val albumIds = photoDao.albumIdsForAny(sourceIds)
        val albumFolder = albumIds.firstOrNull()
            ?.let { albumDao.findById(it)?.name?.sanitizedAsFolder() }
            ?: StorageProvider.DEFAULT_ALBUM_FOLDER

        val entity = storeBitmap(
            bitmap = bitmap,
            photoId = photoId,
            displayName = name.trim().ifEmpty { fallbackName },
            albumFolder = albumFolder,
            capturedAtMs = sources.entities.minOf { it.dateTakenMs },
            kind = kind,
        ) ?: return@withContext null

        // The composite belongs wherever its sources lived, so it turns up in the same albums.
        albumIds.forEach { albumId ->
            albumDao.addPhoto(AlbumPhotoCrossRef(albumId, photoId, System.currentTimeMillis()))
        }

        compositeDao.upsert(
            CompositeEntity(
                mergedPhotoId = photoId,
                kind = kind,
                sourcePhotoIds = sourceIds.joinToString(separator = ","),
                createdAtMs = System.currentTimeMillis(),
                aligned = aligned,
            ),
        )

        if (mode == MergeMode.GROUPED) {
            photoDao.assignStack(
                stackId = UUID.randomUUID().toString(),
                primaryId = photoId,
                photoIds = sourceIds + photoId,
            )
        }
        entity
    }

    /** Releases a stack's members back into the grid, leaving every photo intact. */
    suspend fun ungroup(stackId: String) = withContext(ioDispatcher) {
        photoDao.dissolveStack(stackId)
    }

    /** Source photo ids behind a composite, in the order they were combined. */
    suspend fun sourcesOf(photoId: String): List<String> = withContext(ioDispatcher) {
        compositeDao.findByPhotoId(photoId)?.sourcePhotoIds
            ?.split(',')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private suspend fun storeBitmap(
        bitmap: Bitmap,
        photoId: String,
        displayName: String,
        albumFolder: String,
        capturedAtMs: Long,
        kind: PhotoKind,
    ): PhotoEntity? {
        val staged = File(context.cacheDir, "composite-$photoId.jpg")
        try {
            staged.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, COMPOSITE_QUALITY, out)) return null
            }

            val targetPath = StorageProvider.originalPath(albumFolder, photoId)
            if (storage.uploadPhoto(staged, targetPath) !is StorageResult.Success) return null

            val entity = PhotoEntity(
                id = photoId,
                displayName = displayName,
                originalPath = targetPath,
                thumbnailPath = photoRepository.writeThumbnailFor(staged, photoId),
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = staged.length(),
                dateAddedMs = System.currentTimeMillis(),
                dateTakenMs = capturedAtMs,
                kind = kind,
            )
            photoDao.upsert(entity)
            return entity
        } catch (t: Throwable) {
            return null
        } finally {
            staged.delete()
        }
    }

    private companion object {
        /** Already an 8-bit render; keep it near-lossless so later editing has something to work with. */
        const val COMPOSITE_QUALITY = 95
    }
}
