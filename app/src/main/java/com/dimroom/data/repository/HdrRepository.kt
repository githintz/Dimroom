package com.dimroom.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.dimroom.data.db.AlbumDao
import com.dimroom.data.db.AlbumPhotoCrossRef
import com.dimroom.data.db.HdrDao
import com.dimroom.data.db.HdrMergeEntity
import com.dimroom.data.db.PhotoDao
import com.dimroom.data.db.PhotoEntity
import com.dimroom.data.storage.StorageProvider
import com.dimroom.data.storage.StorageResult
import com.dimroom.data.storage.sanitizedAsFolder
import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.HdrMergeMode
import com.dimroom.domain.model.HdrMergeRequest
import com.dimroom.domain.model.PhotoKind
import com.dimroom.editor.hdr.HdrMerger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a merge, as the library screen needs to describe it. */
sealed interface HdrMergeOutcome {
    data class Success(
        val mergedPhotoId: String,
        val displayName: String,
        val sourceCount: Int,
        val mode: HdrMergeMode,
        val alignedFrames: Int,
    ) : HdrMergeOutcome

    data class Failure(val message: String) : HdrMergeOutcome
}

/**
 * Merges a bracket into a new library photo.
 *
 * The merged result is an ordinary photo: real bytes under `/Originals`, its own thumbnail, and a
 * fresh editable edit stack. Nothing about it is special-cased downstream, so every existing feature
 * — editing, presets, export, albums — works on an HDR exactly as it does on an import.
 */
@Singleton
class HdrRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val merger: HdrMerger,
    private val storage: StorageProvider,
    private val photoRepository: PhotoRepository,
    private val photoDao: PhotoDao,
    private val albumDao: AlbumDao,
    private val hdrDao: HdrDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun merge(
        request: HdrMergeRequest,
        listener: HdrMerger.ProgressListener = HdrMerger.ProgressListener { _, _ -> },
    ): HdrMergeOutcome = withContext(ioDispatcher) {
        val sources = photoDao.findAllById(request.sourcePhotoIds)
            // findAllById does not preserve the requested order, and the first frame is the
            // alignment reference, so restore the user's selection order explicitly.
            .sortedBy { request.sourcePhotoIds.indexOf(it.id) }
        if (sources.size < HdrMerger.MIN_FRAMES) {
            return@withContext HdrMergeOutcome.Failure("Pick at least ${HdrMerger.MIN_FRAMES} photos to merge")
        }

        val files = sources.mapNotNull { photoRepository.localFileFor(it.originalPath) }
        if (files.size != sources.size) {
            return@withContext HdrMergeOutcome.Failure("Some of the selected photos could not be opened")
        }

        val merged = when (val result = merger.merge(files, request.alignFrames, listener)) {
            is HdrMerger.Result.Failure -> return@withContext HdrMergeOutcome.Failure(result.message)
            is HdrMerger.Result.Success -> result
        }

        try {
            val photoId = UUID.randomUUID().toString()
            val albumIds = photoDao.albumIdsForAny(request.sourcePhotoIds)
            val albumFolder = albumIds.firstOrNull()
                ?.let { albumDao.findById(it)?.name?.let(::folderFor) }
                ?: StorageProvider.DEFAULT_ALBUM_FOLDER

            val entity = writeMergedPhoto(
                bitmap = merged.bitmap,
                photoId = photoId,
                displayName = request.name.trim().ifEmpty { "HDR merge" },
                albumFolder = albumFolder,
                capturedAtMs = sources.minOf { it.dateTakenMs },
            ) ?: return@withContext HdrMergeOutcome.Failure("Could not save the merged photo")

            // The merge belongs wherever its brackets lived, so it turns up in the same albums.
            albumIds.forEach { albumId ->
                albumDao.addPhoto(AlbumPhotoCrossRef(albumId, photoId, System.currentTimeMillis()))
            }

            hdrDao.upsert(
                HdrMergeEntity(
                    mergedPhotoId = photoId,
                    sourcePhotoIds = request.sourcePhotoIds.joinToString(separator = ","),
                    createdAtMs = System.currentTimeMillis(),
                    aligned = request.alignFrames,
                ),
            )

            if (request.mode == HdrMergeMode.GROUPED) {
                val stackId = UUID.randomUUID().toString()
                photoDao.assignStack(
                    stackId = stackId,
                    primaryId = photoId,
                    photoIds = sources.map { it.id } + photoId,
                )
            }

            HdrMergeOutcome.Success(
                mergedPhotoId = entity.id,
                displayName = entity.displayName,
                sourceCount = sources.size,
                mode = request.mode,
                alignedFrames = merged.alignedFrames,
            )
        } finally {
            merged.bitmap.recycle()
        }
    }

    /** Releases a stack's members back into the grid, leaving every photo intact. */
    suspend fun ungroup(stackId: String) = withContext(ioDispatcher) {
        photoDao.dissolveStack(stackId)
    }

    /** Source photo ids behind a merged photo, in the order they were fused. */
    suspend fun sourcesOf(photoId: String): List<String> = withContext(ioDispatcher) {
        hdrDao.findByPhotoId(photoId)?.sourcePhotoIds
            ?.split(',')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private suspend fun writeMergedPhoto(
        bitmap: Bitmap,
        photoId: String,
        displayName: String,
        albumFolder: String,
        capturedAtMs: Long,
    ): PhotoEntity? {
        val staged = File(context.cacheDir, "hdr-$photoId.jpg")
        try {
            staged.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, MERGED_QUALITY, out)) return null
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
                kind = PhotoKind.HDR_MERGE,
            )
            photoDao.upsert(entity)
            return entity
        } catch (t: Throwable) {
            return null
        } finally {
            staged.delete()
        }
    }

    private fun folderFor(albumName: String) = albumName.sanitizedAsFolder()

    private companion object {
        /** Fusion output is already an 8-bit render; keep it near-lossless for later editing. */
        const val MERGED_QUALITY = 95
    }
}
