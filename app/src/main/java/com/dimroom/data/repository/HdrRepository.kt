package com.dimroom.data.repository

import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.HdrMergeRequest
import com.dimroom.domain.model.MergeMode
import com.dimroom.domain.model.PhotoKind
import com.dimroom.editor.hdr.HdrMerger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a merge, as the library screen needs to describe it. */
sealed interface HdrMergeOutcome {
    data class Success(
        val mergedPhotoId: String,
        val displayName: String,
        val sourceCount: Int,
        val mode: MergeMode,
        val alignedFrames: Int,
    ) : HdrMergeOutcome

    data class Failure(val message: String) : HdrMergeOutcome
}

/**
 * Fuses a bracket into a new library photo.
 *
 * Owns the exposure side only; putting the result into the library is [CompositeWriter]'s job, which
 * panorama shares.
 */
@Singleton
class HdrRepository @Inject constructor(
    private val merger: HdrMerger,
    private val writer: CompositeWriter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun merge(
        request: HdrMergeRequest,
        listener: HdrMerger.ProgressListener = HdrMerger.ProgressListener { _, _ -> },
    ): HdrMergeOutcome = withContext(ioDispatcher) {
        if (request.sourcePhotoIds.size < HdrMerger.MIN_FRAMES) {
            return@withContext HdrMergeOutcome.Failure(
                "Pick at least ${HdrMerger.MIN_FRAMES} photos to merge",
            )
        }

        val sources = writer.resolveSources(request.sourcePhotoIds)
            ?: return@withContext HdrMergeOutcome.Failure(
                "Some of the selected photos could not be opened",
            )

        val merged = when (val result = merger.merge(sources.files, request.alignFrames, listener)) {
            is HdrMerger.Result.Failure -> return@withContext HdrMergeOutcome.Failure(result.message)
            is HdrMerger.Result.Success -> result
        }

        try {
            val entity = writer.write(
                bitmap = merged.bitmap,
                sources = sources,
                name = request.name,
                fallbackName = "HDR merge",
                kind = PhotoKind.HDR_MERGE,
                mode = request.mode,
                aligned = request.alignFrames,
            ) ?: return@withContext HdrMergeOutcome.Failure("Could not save the merged photo")

            HdrMergeOutcome.Success(
                mergedPhotoId = entity.id,
                displayName = entity.displayName,
                sourceCount = sources.entities.size,
                mode = request.mode,
                alignedFrames = merged.alignedFrames,
            )
        } finally {
            merged.bitmap.recycle()
        }
    }

    /** Releases a stack's members back into the grid, leaving every photo intact. */
    suspend fun ungroup(stackId: String) = writer.ungroup(stackId)

    /** Source photo ids behind a merged photo, in the order they were fused. */
    suspend fun sourcesOf(photoId: String): List<String> = writer.sourcesOf(photoId)
}
