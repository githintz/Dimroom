package com.dimroom.editor.hdr

import kotlin.math.abs

/**
 * Median Threshold Bitmap alignment (Ward, 2003).
 *
 * Handheld brackets are never pixel-aligned, and fusing misaligned frames produces ghosting that no
 * amount of clever blending hides. Thresholding each frame at its own median makes the comparison
 * almost immune to the exposure differences that define a bracket — a plain difference metric would
 * just report "these frames have different brightness" and align nothing.
 *
 * Translation only: rotation and parallax are out of scope, so a badly swung handheld set will still
 * ghost. Pure integer maths on grayscale buffers, so it is fully unit-testable off-device.
 */
object MtbAligner {

    /** Pixels this close to the median carry no reliable information and are excluded. */
    private const val NOISE_TOLERANCE = 4

    /** Grayscale image as one byte-valued sample per pixel, 0..255. */
    class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
        init {
            require(pixels.size == width * height) {
                "Buffer of ${pixels.size} does not match ${width}x$height"
            }
        }
    }

    data class Offset(val dx: Int, val dy: Int) {
        val isZero: Boolean get() = dx == 0 && dy == 0
    }

    /**
     * Finds the shift that best maps [target] onto [reference], returning the offset to apply to
     * [target].
     *
     * ### Reach
     * Recoverable shift is `2^(h+1) - 1` pixels, where `h` is the number of halvings — bounded by
     * [maxShiftBits] and, in practice more often, by the image size, since halving stops at 32 px.
     * So reach scales with the frame:
     *
     * | Frame | Halvings | Reach |
     * | --- | --- | --- |
     * | 256 px | 3 | +/-15 px |
     * | 512 px | 4 | +/-31 px |
     * | 2048 px | 6 | +/-127 px |
     *
     * [HdrMerger] aligns at its working resolution, typically one to two thousand pixels, so this
     * comfortably covers realistic handheld drift of a few percent of the frame. A shift beyond
     * reach is not detected as a failure — it simply returns the best offset it could walk to.
     */
    fun align(reference: GrayImage, target: GrayImage, maxShiftBits: Int = 6): Offset {
        require(reference.width == target.width && reference.height == target.height) {
            "Alignment needs images of equal size"
        }
        val levels = maxShiftBits.coerceIn(0, 8)
        return alignRecursive(reference, target, levels)
    }

    private fun alignRecursive(reference: GrayImage, target: GrayImage, level: Int): Offset {
        // Recurse first: solving the coarse image gives a starting point that this level only has
        // to nudge by a pixel, which is what keeps the search to nine candidates per level.
        //
        // Reach comes from depth, not from a wider search. Widening the search at the bottom is
        // tempting and does not work: the coarsest bitmap has the fewest pixels and the most
        // exclusions, so extra candidates there mostly buy spurious matches that the single-pixel
        // refinements above can no longer walk back.
        var current = Offset(0, 0)
        if (level > 0 && reference.width > MIN_SIZE && reference.height > MIN_SIZE) {
            val coarse = alignRecursive(halve(reference), halve(target), level - 1)
            current = Offset(coarse.dx * 2, coarse.dy * 2)
        }

        val referenceBitmap = thresholdAtMedian(reference)
        val targetBitmap = thresholdAtMedian(target)

        var best = current
        var bestError = Int.MAX_VALUE
        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                val candidate = Offset(current.dx + dx, current.dy + dy)
                val error = disagreement(referenceBitmap, targetBitmap, candidate)
                if (error < bestError) {
                    bestError = error
                    best = candidate
                }
            }
        }
        return best
    }

    /**
     * Thresholds at the median and marks near-median pixels as unusable.
     *
     * The median specifically — not the mean — is what makes this exposure invariant: changing the
     * shutter speed moves every pixel's value but barely moves which half of the histogram it is in.
     */
    private fun thresholdAtMedian(image: GrayImage): ThresholdBitmap {
        val histogram = IntArray(256)
        for (pixel in image.pixels) histogram[pixel.coerceIn(0, 255)]++

        val half = image.pixels.size / 2
        var running = 0
        var median = 0
        for (value in 0..255) {
            running += histogram[value]
            if (running > half) {
                median = value
                break
            }
        }

        val above = BooleanArray(image.pixels.size)
        val usable = BooleanArray(image.pixels.size)
        for (i in image.pixels.indices) {
            val value = image.pixels[i]
            above[i] = value > median
            usable[i] = abs(value - median) > NOISE_TOLERANCE
        }
        return ThresholdBitmap(image.width, image.height, above, usable)
    }

    /** Count of pixels where the two bitmaps disagree, ignoring the excluded band. */
    private fun disagreement(reference: ThresholdBitmap, target: ThresholdBitmap, offset: Offset): Int {
        var errors = 0
        for (y in 0 until reference.height) {
            val sourceY = y - offset.dy
            if (sourceY < 0 || sourceY >= target.height) continue
            for (x in 0 until reference.width) {
                val sourceX = x - offset.dx
                if (sourceX < 0 || sourceX >= target.width) continue
                val referenceIndex = y * reference.width + x
                val targetIndex = sourceY * target.width + sourceX
                if (!reference.usable[referenceIndex] || !target.usable[targetIndex]) continue
                if (reference.above[referenceIndex] != target.above[targetIndex]) errors++
            }
        }
        return errors
    }

    /** Box-averaged half-resolution copy. */
    internal fun halve(image: GrayImage): GrayImage {
        val width = (image.width + 1) / 2
        val height = (image.height + 1) / 2
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val y0 = (y * 2).coerceAtMost(image.height - 1)
            val y1 = (y * 2 + 1).coerceAtMost(image.height - 1)
            for (x in 0 until width) {
                val x0 = (x * 2).coerceAtMost(image.width - 1)
                val x1 = (x * 2 + 1).coerceAtMost(image.width - 1)
                val sum = image.pixels[y0 * image.width + x0] +
                    image.pixels[y0 * image.width + x1] +
                    image.pixels[y1 * image.width + x0] +
                    image.pixels[y1 * image.width + x1]
                pixels[y * width + x] = sum / 4
            }
        }
        return GrayImage(width, height, pixels)
    }

    private class ThresholdBitmap(
        val width: Int,
        val height: Int,
        val above: BooleanArray,
        val usable: BooleanArray,
    )

    /**
     * Floor on the coarsest pyramid level. Below roughly this size the median threshold stops
     * describing anything structural and the match becomes a coin toss, which then poisons every
     * refinement above it.
     */
    private const val MIN_SIZE = 32

    /** One pixel at every level: the level below has already done the locating. */
    private const val SEARCH_RADIUS = 1
}
