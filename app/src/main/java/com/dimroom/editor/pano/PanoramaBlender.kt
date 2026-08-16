package com.dimroom.editor.pano

import com.dimroom.editor.hdr.FloatImage
import com.dimroom.editor.hdr.Pyramids
import kotlin.math.abs
import kotlin.math.min

/**
 * Turns placed frames into one seamless image.
 *
 * Two problems have to be solved together. Frames differ in exposure because the camera metered each
 * one separately, and they differ in alignment by a pixel or two however good the estimate was. Gain
 * compensation fixes the first; multi-band blending hides the second by crossing over slowly at low
 * frequencies and quickly at high ones — a single feathered blend can only pick one width, and gets
 * either a visible seam or a smeared one.
 *
 * The API is deliberately streaming. A five-frame sweep at canvas size is several hundred megabytes
 * if every frame is resident, so callers fold one frame in at a time and only the running result
 * pyramid persists.
 */
object PanoramaBlender {

    /**
     * Accumulates weighted Laplacian bands, one frame at a time.
     *
     * Same machinery as exposure fusion: a per-band weighted sum, collapsed back down. Only the
     * choice of weights differs, which is why [Pyramids] is shared rather than reimplemented.
     */
    class Accumulator(width: Int, height: Int, maxLevels: Int = 8) {

        private val levels = Pyramids.levelsFor(width, height, maxLevels)
        private var bands: MutableList<FloatImage>? = null

        /** Folds in one frame. [normalisedWeight] must already sum to one across all frames. */
        fun add(image: FloatImage, normalisedWeight: FloatImage) {
            require(image.channels == RGB) { "Panorama blending expects three-channel frames" }
            require(normalisedWeight.channels == 1) { "Weights are a single-channel map" }

            val laplacian = Pyramids.laplacianPyramid(image, levels)
            val weightPyramid = Pyramids.gaussianPyramid(normalisedWeight, levels)
            val target = bands ?: MutableList(levels) { level ->
                FloatImage(laplacian[level].width, laplacian[level].height, RGB)
            }.also { bands = it }

            for (level in 0 until levels) {
                val band = laplacian[level]
                val weight = weightPyramid[level]
                val destination = target[level]
                for (pixel in 0 until band.pixelCount) {
                    val w = weight.data[pixel]
                    if (w == 0f) continue
                    val base = pixel * RGB
                    destination.data[base] += w * band.data[base]
                    destination.data[base + 1] += w * band.data[base + 1]
                    destination.data[base + 2] += w * band.data[base + 2]
                }
            }
        }

        fun collapse(): FloatImage = Pyramids.collapse(checkNotNull(bands) { "Nothing was blended" })
            .clampInPlace()
    }

    /**
     * Weight map falling off towards a frame's own edges.
     *
     * Distance to the nearest uncovered pixel, so a frame's centre dominates and its border yields.
     * That puts every seam where both frames are at their weakest, rather than at an arbitrary
     * rectangle boundary.
     */
    fun edgeDistanceWeights(coverage: FloatImage): FloatImage {
        val width = coverage.width
        val height = coverage.height
        val distance = FloatImage(width, height, 1)
        val large = (width + height).toFloat()

        // Everything outside the buffer counts as uncovered, so a neighbour off the edge is at
        // distance zero. Without that, a mask covering the whole canvas has no seeds anywhere and
        // every pixel keeps its initial value — a flat weight map, and no blend at all.
        fun neighbour(inBounds: Boolean, index: Int): Float =
            if (inBounds) distance.data[index] + 1f else 1f

        // Two-pass chamfer transform: forward sweep then backward.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (coverage.data[index] < 0.5f) {
                    distance.data[index] = 0f
                    continue
                }
                var best = large
                best = min(best, neighbour(x > 0, index - 1))
                best = min(best, neighbour(y > 0, index - width))
                distance.data[index] = best
            }
        }
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val index = y * width + x
                if (coverage.data[index] < 0.5f) continue
                var best = distance.data[index]
                best = min(best, neighbour(x < width - 1, index + 1))
                best = min(best, neighbour(y < height - 1, index + width))
                distance.data[index] = best
            }
        }
        return distance
    }

    /**
     * Exposure ratio between two overlapping frames, or null when they barely meet.
     *
     * Clamped, because a wild ratio means the overlap estimate is wrong and applying it would do
     * more damage than the exposure difference it is trying to correct.
     */
    fun overlapGain(
        reference: FloatImage,
        referenceCoverage: FloatImage,
        frame: FloatImage,
        frameCoverage: FloatImage,
    ): Float? {
        var referenceSum = 0.0
        var frameSum = 0.0
        var overlap = 0

        for (pixel in 0 until frameCoverage.pixelCount) {
            if (referenceCoverage.data[pixel] < 0.5f || frameCoverage.data[pixel] < 0.5f) continue
            val base = pixel * RGB
            referenceSum += luminance(reference, base)
            frameSum += luminance(frame, base)
            overlap++
        }

        if (overlap < MIN_OVERLAP_PIXELS || frameSum < 1e-6) return null
        return (referenceSum / frameSum).toFloat().coerceIn(MIN_GAIN, MAX_GAIN)
    }

    /** Multiplies a frame by its gain, sharing the buffer when the gain is a no-op. */
    fun applyGain(image: FloatImage, gain: Float): FloatImage {
        if (abs(gain - 1f) < 1e-4f) return image
        val out = FloatImage(image.width, image.height, image.channels)
        for (i in image.data.indices) out.data[i] = image.data[i] * gain
        return out
    }

    /**
     * Normalises one frame's weight against the total across all frames.
     *
     * Callers accumulate [totals] in a first pass and recompute each frame's own weight in a second,
     * rather than holding every weight map at once.
     */
    fun normaliseAgainst(weight: FloatImage, totals: FloatArray): FloatImage {
        val out = FloatImage(weight.width, weight.height, 1)
        for (pixel in 0 until weight.pixelCount) {
            val total = totals[pixel]
            // Gaps no frame covers stay at zero rather than becoming grey.
            out.data[pixel] = if (total > 1e-6f) weight.data[pixel] / total else 0f
        }
        return out
    }

    private fun luminance(image: FloatImage, base: Int): Float =
        0.299f * image.data[base] + 0.587f * image.data[base + 1] + 0.114f * image.data[base + 2]

    private const val RGB = 3
    private const val MIN_OVERLAP_PIXELS = 200
    private const val MIN_GAIN = 0.4f
    private const val MAX_GAIN = 2.5f
}
