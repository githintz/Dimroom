package com.dimroom.editor.hdr

import kotlin.math.log2
import kotlin.math.min

/**
 * Gaussian and Laplacian pyramid construction.
 *
 * Fusing exposures at a single scale produces visible seams and halos wherever the weight map moves
 * quickly; blending each frequency band separately is what makes the result look like one
 * photograph. Filters are the standard separable binomial kernel with clamped edges.
 */
object Pyramids {

    /** Binomial approximation to a Gaussian, normalised for the reduce step. */
    private val REDUCE_KERNEL = floatArrayOf(1f / 16f, 4f / 16f, 6f / 16f, 4f / 16f, 1f / 16f)

    /**
     * The same kernel at twice the gain. Inserting zeros between samples halves the signal's
     * amplitude per axis, so the expand filter has to put that factor back.
     */
    private val EXPAND_KERNEL = floatArrayOf(1f / 8f, 4f / 8f, 6f / 8f, 4f / 8f, 1f / 8f)

    private const val KERNEL_RADIUS = 2

    /** Deepest useful pyramid for an image of this size: stop before the top gets tiny. */
    fun levelsFor(width: Int, height: Int, maxLevels: Int = 8): Int {
        val smallest = min(width, height)
        if (smallest < MIN_TOP_SIZE * 2) return 1
        val possible = log2(smallest.toDouble() / MIN_TOP_SIZE).toInt() + 1
        return possible.coerceIn(1, maxLevels)
    }

    /** Blurs then decimates by two. */
    fun reduce(source: FloatImage): FloatImage {
        val blurred = blur(source, REDUCE_KERNEL)
        val width = (source.width + 1) / 2
        val height = (source.height + 1) / 2
        val out = FloatImage(width, height, source.channels)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val src = blurred.offset(
                    (x * 2).coerceAtMost(source.width - 1),
                    (y * 2).coerceAtMost(source.height - 1),
                )
                val dst = out.offset(x, y)
                for (c in 0 until source.channels) out.data[dst + c] = blurred.data[src + c]
            }
        }
        return out
    }

    /**
     * Upsamples to exactly [targetWidth] x [targetHeight].
     *
     * The explicit target matters: reduce rounds odd sizes up, so a naive doubling would drift by a
     * pixel and the Laplacian levels would stop lining up.
     */
    fun expand(source: FloatImage, targetWidth: Int, targetHeight: Int): FloatImage {
        val upsampled = FloatImage(targetWidth, targetHeight, source.channels)
        for (y in 0 until source.height) {
            val ty = y * 2
            if (ty >= targetHeight) continue
            for (x in 0 until source.width) {
                val tx = x * 2
                if (tx >= targetWidth) continue
                val src = source.offset(x, y)
                val dst = upsampled.offset(tx, ty)
                for (c in 0 until source.channels) upsampled.data[dst + c] = source.data[src + c]
            }
        }
        return blur(upsampled, EXPAND_KERNEL)
    }

    fun gaussianPyramid(source: FloatImage, levels: Int): List<FloatImage> {
        val pyramid = ArrayList<FloatImage>(levels)
        var current = source
        pyramid.add(current)
        repeat(levels - 1) {
            current = reduce(current)
            pyramid.add(current)
        }
        return pyramid
    }

    /** Band-pass levels plus the residual low-pass at the top. */
    fun laplacianPyramid(source: FloatImage, levels: Int): List<FloatImage> {
        val gaussian = gaussianPyramid(source, levels)
        val pyramid = ArrayList<FloatImage>(levels)
        for (level in 0 until levels - 1) {
            val fine = gaussian[level]
            val coarse = expand(gaussian[level + 1], fine.width, fine.height)
            val band = FloatImage(fine.width, fine.height, fine.channels)
            for (i in band.data.indices) band.data[i] = fine.data[i] - coarse.data[i]
            pyramid.add(band)
        }
        pyramid.add(gaussian[levels - 1])
        return pyramid
    }

    /** Inverse of [laplacianPyramid]: sums the bands back into a single image. */
    fun collapse(pyramid: List<FloatImage>): FloatImage {
        require(pyramid.isNotEmpty()) { "Cannot collapse an empty pyramid" }
        var current = pyramid.last().copy()
        for (level in pyramid.size - 2 downTo 0) {
            val band = pyramid[level]
            val expanded = expand(current, band.width, band.height)
            for (i in expanded.data.indices) expanded.data[i] += band.data[i]
            current = expanded
        }
        return current
    }

    /** Separable convolution with edge clamping. */
    private fun blur(source: FloatImage, kernel: FloatArray): FloatImage {
        val channels = source.channels
        val horizontal = FloatImage(source.width, source.height, channels)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val dst = horizontal.offset(x, y)
                for (c in 0 until channels) {
                    var sum = 0f
                    for (k in kernel.indices) {
                        sum += kernel[k] * source.clamped(x + k - KERNEL_RADIUS, y, c)
                    }
                    horizontal.data[dst + c] = sum
                }
            }
        }

        val vertical = FloatImage(source.width, source.height, channels)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val dst = vertical.offset(x, y)
                for (c in 0 until channels) {
                    var sum = 0f
                    for (k in kernel.indices) {
                        sum += kernel[k] * horizontal.clamped(x, y + k - KERNEL_RADIUS, c)
                    }
                    vertical.data[dst + c] = sum
                }
            }
        }
        return vertical
    }

    private const val MIN_TOP_SIZE = 8
}
