package com.dimroom.editor.hdr

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.getSystemService
import com.dimroom.data.repository.ImageOrientation
import com.dimroom.di.DefaultDispatcher
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
 * Turns a bracket of files into one fused image.
 *
 * This is the Android-facing half of HDR: decoding, orientation, sizing and memory budgeting. The
 * actual maths lives in [ExposureFusion] and [MtbAligner], which know nothing about Android and are
 * covered by unit tests.
 */
@Singleton
class HdrMerger @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /** Progress in 0..1 alongside a short description of the current phase. */
    fun interface ProgressListener {
        fun onProgress(fraction: Float, stage: String)
    }

    sealed interface Result {
        data class Success(val bitmap: Bitmap, val alignedFrames: Int) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Fuses [files] into a single bitmap.
     *
     * Frames are decoded to a common working resolution chosen from the device's heap budget, so a
     * nine-shot bracket on a modest phone quietly works at a lower resolution rather than dying.
     */
    suspend fun merge(
        files: List<File>,
        align: Boolean,
        listener: ProgressListener = ProgressListener { _, _ -> },
    ): Result = withContext(dispatcher) {
        if (files.size < MIN_FRAMES) {
            return@withContext Result.Failure("Pick at least $MIN_FRAMES photos to merge")
        }
        if (files.size > MAX_FRAMES) {
            return@withContext Result.Failure("HDR merge handles up to $MAX_FRAMES photos at once")
        }

        val targetPixels = workingPixelBudget(files.size)
        val reference = decodeScaled(files.first(), targetPixels)
            ?: return@withContext Result.Failure("Could not read ${files.first().name}")

        val width = reference.width
        val height = reference.height
        val frames = ArrayList<FloatImage>(files.size)
        var alignedFrames = 0

        try {
            // Grayscale copy of the reference, kept only while there is something left to align.
            var referenceGray = if (align) toGray(reference, width, height) else null
            frames.add(toFloatImage(reference))
            listener.onProgress(progressFor(1, files.size), "Reading photos")
            reference.recycle()

            for (index in 1 until files.size) {
                coroutineContext.ensureActive()
                val decoded = decodeScaled(files[index], targetPixels)
                    ?: return@withContext Result.Failure("Could not read ${files[index].name}")

                // Every frame has to land on the reference's grid before it can be fused.
                val sized = if (decoded.width != width || decoded.height != height) {
                    Bitmap.createScaledBitmap(decoded, width, height, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                } else {
                    decoded
                }

                val offset = referenceGray?.let { anchor ->
                    MtbAligner.align(anchor, toGray(sized, width, height))
                }
                if (offset != null && !offset.isZero) alignedFrames++

                frames.add(toFloatImage(sized, offset))
                sized.recycle()
                listener.onProgress(progressFor(index + 1, files.size), "Reading photos")
            }
            referenceGray = null

            coroutineContext.ensureActive()
            listener.onProgress(FUSION_START, "Merging exposures")
            val fused = ExposureFusion.fuse(frames)
            frames.clear()

            listener.onProgress(0.95f, "Finishing")
            Result.Success(toBitmap(fused), alignedFrames)
        } catch (error: OutOfMemoryError) {
            frames.clear()
            Result.Failure("Ran out of memory merging ${files.size} photos. Try merging fewer.")
        }
    }

    /**
     * Longest-edge-agnostic pixel budget for the working resolution.
     *
     * Fusion holds, per frame, an RGB float buffer and a weight map, plus a result pyramid and the
     * pyramids of the frame being folded in. Deriving the size from the device's own heap class is
     * what keeps a big bracket from becoming an OOM on a small phone.
     */
    private fun workingPixelBudget(frameCount: Int): Int {
        val heapMegabytes = context.getSystemService<ActivityManager>()?.largeMemoryClass ?: 128
        val budgetBytes = heapMegabytes * 1024L * 1024L * HEAP_FRACTION

        // Per pixel: frameCount RGB floats + frameCount weight floats, then roughly a third again
        // for the pyramids and result accumulator.
        val bytesPerPixel = (frameCount * (3 + 1)) * Float.SIZE_BYTES * PYRAMID_OVERHEAD
        return (budgetBytes / bytesPerPixel).toInt().coerceIn(MIN_WORKING_PIXELS, MAX_WORKING_PIXELS)
    }

    /** Decodes with EXIF orientation applied, downsampled to at most [targetPixels]. */
    private fun decodeScaled(file: File, targetPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode to at most twice the budget and trim exactly afterwards: inSampleSize only moves
        // in powers of two, so decoding straight down to the budget can cost half the resolution.
        var sampleSize = 1
        var pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
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

        pixels = oriented.width.toLong() * oriented.height.toLong()
        if (pixels <= targetPixels) return oriented

        // inSampleSize only moves in powers of two, so trim the remainder exactly.
        val scale = sqrt(targetPixels.toDouble() / pixels).toFloat()
        val scaled = Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun toGray(bitmap: Bitmap, width: Int, height: Int): MtbAligner.GrayImage {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val gray = IntArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return MtbAligner.GrayImage(width, height, gray)
    }

    /** Converts to float, optionally sampling through an alignment [offset] with clamped edges. */
    private fun toFloatImage(bitmap: Bitmap, offset: MtbAligner.Offset? = null): FloatImage {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val image = FloatImage(width, height, 3)
        val dx = offset?.dx ?: 0
        val dy = offset?.dy ?: 0
        for (y in 0 until height) {
            val sourceY = if (dy == 0) y else (y - dy).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val sourceX = if (dx == 0) x else (x - dx).coerceIn(0, width - 1)
                val pixel = argb[sourceY * width + sourceX]
                val target = image.offset(x, y)
                image.data[target] = ((pixel shr 16) and 0xFF) / 255f
                image.data[target + 1] = ((pixel shr 8) and 0xFF) / 255f
                image.data[target + 2] = (pixel and 0xFF) / 255f
            }
        }
        return image
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

    /** Decoding occupies the first stretch of the bar; fusion owns the rest. */
    private fun progressFor(done: Int, total: Int): Float = FUSION_START * done / total

    companion object {
        const val MIN_FRAMES = 2
        const val MAX_FRAMES = 9

        private const val HEAP_FRACTION = 0.40
        private const val PYRAMID_OVERHEAD = 1.4
        private const val FUSION_START = 0.45f
        private const val MIN_WORKING_PIXELS = 512 * 512
        private const val MAX_WORKING_PIXELS = 12_000_000
    }
}
