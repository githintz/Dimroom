package com.dimroom.editor.pano

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.getSystemService
import androidx.exifinterface.media.ExifInterface
import com.dimroom.data.repository.ImageOrientation
import com.dimroom.di.DefaultDispatcher
import com.dimroom.editor.hdr.FloatImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Stitches a sequence of overlapping frames into one panorama.
 *
 * Assumes the frames are given in sweep order and that each overlaps its neighbour. That assumption
 * is what allows adjacent-pair matching instead of an all-pairs search, and it matches how people
 * actually shoot: left to right, or right to left.
 */
@Singleton
class PanoramaStitcher @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    fun interface ProgressListener {
        fun onProgress(fraction: Float, stage: String)
    }

    sealed interface Result {
        data class Success(
            val bitmap: Bitmap,
            val frameCount: Int,
            val horizontalFovDegrees: Float,
            val weakestOverlap: Float,
        ) : Result

        /** [failedPair] is the 1-based index of the frame that could not be joined to its predecessor. */
        data class NoOverlap(val failedPair: Int) : Result

        data class Failure(val message: String) : Result
    }

    suspend fun stitch(
        files: List<File>,
        listener: ProgressListener = ProgressListener { _, _ -> },
    ): Result = withContext(dispatcher) {
        if (files.size < MIN_FRAMES) {
            return@withContext Result.Failure("Pick at least $MIN_FRAMES photos to stitch")
        }
        if (files.size > MAX_FRAMES) {
            return@withContext Result.Failure("Panorama stitching handles up to $MAX_FRAMES photos")
        }

        try {
            val framePixels = framePixelBudget()
            val probe = decodeProjected(files.first(), framePixels)
                ?: return@withContext Result.Failure("Could not read ${files.first().name}")
            val focalPixels = probe.focalPixels
            val fov = CylindricalProjection.horizontalFovDegrees(focalPixels, probe.sourceWidth)

            // Pass one: features only. Each projected frame is discarded as soon as it has been
            // described, so peak memory here is one frame rather than the whole sweep.
            listener.onProgress(0.05f, "Finding features")
            val featureSets = ArrayList<List<Feature>>(files.size)
            val frameSizes = ArrayList<Pair<Int, Int>>(files.size)
            files.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                val projected = if (index == 0) {
                    probe
                } else {
                    decodeProjected(file, framePixels, focalPixels)
                        ?: return@withContext Result.Failure("Could not read ${file.name}")
                }
                featureSets.add(FeatureDetector.detect(toGray(projected.image)))
                frameSizes.add(projected.image.width to projected.image.height)
                listener.onProgress(
                    0.05f + FEATURE_SHARE * (index + 1) / files.size,
                    "Finding features",
                )
            }

            listener.onProgress(0.05f + FEATURE_SHARE, "Aligning frames")
            val pairwise = ArrayList<Similarity>(files.size - 1)
            var weakestOverlap = 1f
            for (index in 0 until files.size - 1) {
                coroutineContext.ensureActive()
                // Estimate the transform taking frame index+1 into frame index.
                val matches = SimilarityEstimator.match(featureSets[index + 1], featureSets[index])
                val estimate = SimilarityEstimator.estimate(matches)
                if (estimate == null ||
                    estimate.inliers < MIN_INLIERS ||
                    estimate.inlierRatio < MIN_INLIER_RATIO
                ) {
                    return@withContext Result.NoOverlap(index + 1)
                }
                pairwise.add(estimate.transform)
                weakestOverlap = minOf(weakestOverlap, estimate.inlierRatio)
            }

            val absolute = PanoramaCanvas.chain(pairwise)
            val layout = PanoramaCanvas.layout(absolute, frameSizes, canvasPixelBudget())

            // Pass two: place each frame to accumulate blend weights and per-frame exposure gains.
            listener.onProgress(0.45f, "Balancing exposure")
            val totals = FloatArray(layout.width * layout.height)
            val gains = FloatArray(files.size) { 1f }
            var previous: PlacedFrame? = null

            files.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                val placed = placeFrame(file, framePixels, focalPixels, layout, index)
                    ?: return@withContext Result.Failure("Could not read ${file.name}")

                previous?.let { earlier ->
                    val gain = PanoramaBlender.overlapGain(
                        reference = PanoramaBlender.applyGain(earlier.image, gains[index - 1]),
                        referenceCoverage = earlier.coverage,
                        frame = placed.image,
                        frameCoverage = placed.coverage,
                    )
                    gains[index] = gain ?: gains[index - 1]
                }

                val weights = PanoramaBlender.edgeDistanceWeights(placed.coverage)
                for (pixel in totals.indices) totals[pixel] += weights.data[pixel]

                previous = placed
                listener.onProgress(0.45f + WEIGHT_SHARE * (index + 1) / files.size, "Balancing exposure")
            }
            previous = null

            // Pass three: fold each frame into the multi-band accumulator and collapse.
            listener.onProgress(0.65f, "Blending")
            val accumulator = PanoramaBlender.Accumulator(layout.width, layout.height)
            files.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                val placed = placeFrame(file, framePixels, focalPixels, layout, index)
                    ?: return@withContext Result.Failure("Could not read ${file.name}")
                val weights = PanoramaBlender.edgeDistanceWeights(placed.coverage)
                accumulator.add(
                    PanoramaBlender.applyGain(placed.image, gains[index]),
                    PanoramaBlender.normaliseAgainst(weights, totals),
                )
                listener.onProgress(0.65f + BLEND_SHARE * (index + 1) / files.size, "Blending")
            }

            listener.onProgress(0.95f, "Finishing")
            Result.Success(
                bitmap = toBitmap(accumulator.collapse()),
                frameCount = files.size,
                horizontalFovDegrees = fov * files.size,
                weakestOverlap = weakestOverlap,
            )
        } catch (error: OutOfMemoryError) {
            Result.Failure("Ran out of memory stitching ${files.size} photos. Try stitching fewer.")
        }
    }

    private class Projected(
        val image: FloatImage,
        val coverage: FloatImage,
        val focalPixels: Float,
        val sourceWidth: Int,
    )

    /**
     * Decodes, orients and projects one frame onto the cylinder.
     *
     * [knownFocal] pins every frame to the first frame's focal length. Trusting each frame's own
     * EXIF would let a lens switch mid-sweep silently change the projection and pull the panorama
     * apart.
     */
    private fun decodeProjected(
        file: File,
        targetPixels: Int,
        knownFocal: Float? = null,
    ): Projected? {
        val bitmap = decodeScaled(file, targetPixels) ?: return null
        return try {
            val focal = knownFocal ?: estimateFocalPixels(file, bitmap.width)
            val source = toFloatImage(bitmap)
            Projected(
                image = CylindricalProjection.warp(source, focal),
                coverage = CylindricalProjection.coverage(bitmap.width, bitmap.height, focal),
                focalPixels = focal,
                sourceWidth = bitmap.width,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun placeFrame(
        file: File,
        framePixels: Int,
        focalPixels: Float,
        layout: CanvasLayout,
        index: Int,
    ): PlacedFrame? {
        val projected = decodeProjected(file, framePixels, focalPixels) ?: return null
        return FrameWarper.warp(
            frame = projected.image,
            frameCoverage = projected.coverage,
            placement = layout.placements[index],
            canvasWidth = layout.width,
            canvasHeight = layout.height,
        )
    }

    /** Focal length in pixels, from EXIF where possible and a typical phone lens where not. */
    private fun estimateFocalPixels(file: File, imageWidth: Int): Float {
        val fromExif = runCatching {
            val exif = ExifInterface(file.absolutePath)
            val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            if (focal35 > 0) {
                CylindricalProjection.focalPixelsFrom35mm(focal35.toFloat(), imageWidth)
            } else {
                null
            }
        }.getOrNull()

        return fromExif?.takeIf { CylindricalProjection.isPlausibleFocal(it, imageWidth) }
            ?: CylindricalProjection.fallbackFocalPixels(imageWidth)
    }

    /**
     * Per-frame decode budget. Sized so a projected frame plus its features stays modest; the canvas
     * is budgeted separately because it is the far larger allocation.
     */
    private fun framePixelBudget(): Int {
        val heapMegabytes = context.getSystemService<ActivityManager>()?.largeMemoryClass ?: 128
        val budgetBytes = heapMegabytes * 1024L * 1024L * FRAME_HEAP_FRACTION
        // A frame is held as source RGB floats plus its projected copy.
        return (budgetBytes / (3 * Float.SIZE_BYTES * 2)).toInt()
            .coerceIn(MIN_FRAME_PIXELS, MAX_FRAME_PIXELS)
    }

    /**
     * Canvas budget. The blend holds the weight totals, one placed frame, its pyramid and the
     * running result pyramid — roughly fifty-odd bytes per canvas pixel.
     */
    private fun canvasPixelBudget(): Int {
        val heapMegabytes = context.getSystemService<ActivityManager>()?.largeMemoryClass ?: 128
        val budgetBytes = heapMegabytes * 1024L * 1024L * CANVAS_HEAP_FRACTION
        return (budgetBytes / CANVAS_BYTES_PER_PIXEL).toInt()
            .coerceIn(MIN_CANVAS_PIXELS, MAX_CANVAS_PIXELS)
    }

    private fun decodeScaled(file: File, targetPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        while (pixels / (sampleSize.toLong() * sampleSize) > targetPixels.toLong() * 2) sampleSize *= 2

        val raw = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val oriented = ImageOrientation.applyExif(raw, file.absolutePath)
        if (oriented !== raw) raw.recycle()

        val orientedPixels = oriented.width.toLong() * oriented.height.toLong()
        if (orientedPixels <= targetPixels) return oriented

        val scale = sqrt(targetPixels.toDouble() / orientedPixels).toFloat()
        val scaled = Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun toFloatImage(bitmap: Bitmap): FloatImage {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val image = FloatImage(width, height, 3)
        for (pixel in argb.indices) {
            val value = argb[pixel]
            val base = pixel * 3
            image.data[base] = ((value shr 16) and 0xFF) / 255f
            image.data[base + 1] = ((value shr 8) and 0xFF) / 255f
            image.data[base + 2] = (value and 0xFF) / 255f
        }
        return image
    }

    private fun toGray(image: FloatImage): GrayImage {
        val pixels = IntArray(image.pixelCount)
        for (pixel in 0 until image.pixelCount) {
            val base = pixel * 3
            val luminance = 0.299f * image.data[base] +
                0.587f * image.data[base + 1] +
                0.114f * image.data[base + 2]
            pixels[pixel] = (luminance * 255f).toInt().coerceIn(0, 255)
        }
        return GrayImage(image.width, image.height, pixels)
    }

    private fun toBitmap(image: FloatImage): Bitmap {
        val argb = IntArray(image.pixelCount)
        for (pixel in 0 until image.pixelCount) {
            val base = pixel * 3
            val r = (image.data[base] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (image.data[base + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (image.data[base + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            argb[pixel] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(argb, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        const val MIN_FRAMES = 2
        const val MAX_FRAMES = 12

        /**
         * Agreement required to call a pair overlapping.
         *
         * Both numbers are set from measurement rather than intuition. On simulated pans, a genuine
         * overlap of low-texture content yields as few as 14 agreeing correspondences but at a very
         * high ratio, while a non-overlapping pair produces only a handful of raw matches and no fit
         * at all. The count is therefore generous and the ratio does the discriminating; an earlier
         * count of 15 rejected real overlaps for no safety benefit.
         */
        private const val MIN_INLIERS = 8
        private const val MIN_INLIER_RATIO = 0.3f

        private const val FRAME_HEAP_FRACTION = 0.18
        private const val CANVAS_HEAP_FRACTION = 0.40
        private const val CANVAS_BYTES_PER_PIXEL = 56

        private const val MIN_FRAME_PIXELS = 256 * 256
        private const val MAX_FRAME_PIXELS = 4_000_000
        private const val MIN_CANVAS_PIXELS = 512 * 512
        private const val MAX_CANVAS_PIXELS = 40_000_000

        private const val FEATURE_SHARE = 0.35f
        private const val WEIGHT_SHARE = 0.20f
        private const val BLEND_SHARE = 0.30f
    }
}
