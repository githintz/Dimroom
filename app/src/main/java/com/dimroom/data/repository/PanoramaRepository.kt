package com.dimroom.data.repository

import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.MergeMode
import com.dimroom.domain.model.PanoramaRequest
import com.dimroom.domain.model.PhotoKind
import com.dimroom.editor.pano.PanoramaStitcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a stitch, as the library screen needs to describe it. */
sealed interface PanoramaOutcome {
    data class Success(
        val panoramaPhotoId: String,
        val displayName: String,
        val sourceCount: Int,
        val mode: MergeMode,
        val coverageDegrees: Float,
    ) : PanoramaOutcome

    data class Failure(val message: String) : PanoramaOutcome
}

/**
 * Stitches a sweep into a new library photo.
 *
 * Owns the geometry side only; putting the result into the library is [CompositeWriter]'s job, which
 * HDR shares.
 */
@Singleton
class PanoramaRepository @Inject constructor(
    private val stitcher: PanoramaStitcher,
    private val writer: CompositeWriter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun stitch(
        request: PanoramaRequest,
        listener: PanoramaStitcher.ProgressListener = PanoramaStitcher.ProgressListener { _, _ -> },
    ): PanoramaOutcome = withContext(ioDispatcher) {
        if (request.sourcePhotoIds.size < PanoramaStitcher.MIN_FRAMES) {
            return@withContext PanoramaOutcome.Failure(
                "Pick at least ${PanoramaStitcher.MIN_FRAMES} photos to stitch",
            )
        }

        val sources = writer.resolveSources(request.sourcePhotoIds)
            ?: return@withContext PanoramaOutcome.Failure(
                "Some of the selected photos could not be opened",
            )

        val stitched = when (val result = stitcher.stitch(sources.files, listener)) {
            is PanoramaStitcher.Result.Failure -> return@withContext PanoramaOutcome.Failure(result.message)
            // Naming the frame that failed turns an unhelpful "stitching failed" into something the
            // user can act on: reorder the selection, or leave that photo out.
            is PanoramaStitcher.Result.NoOverlap -> return@withContext PanoramaOutcome.Failure(
                "Could not line up photo ${result.failedPair + 1} with the one before it. " +
                    "Check they are in sweep order and overlap by about a third. Very plain " +
                    "surfaces give the matcher little to lock onto — more overlap helps there.",
            )

            is PanoramaStitcher.Result.Success -> result
        }

        try {
            val entity = writer.write(
                bitmap = stitched.bitmap,
                sources = sources,
                name = request.name,
                fallbackName = "Panorama",
                kind = PhotoKind.PANORAMA,
                mode = request.mode,
                aligned = true,
            ) ?: return@withContext PanoramaOutcome.Failure("Could not save the panorama")

            PanoramaOutcome.Success(
                panoramaPhotoId = entity.id,
                displayName = entity.displayName,
                sourceCount = sources.entities.size,
                mode = request.mode,
                coverageDegrees = stitched.horizontalFovDegrees,
            )
        } finally {
            stitched.bitmap.recycle()
        }
    }
}
