package com.dimroom.editor.pano

import com.dimroom.editor.hdr.FloatImage

/**
 * Resamples a cylindrical frame into canvas space.
 *
 * Backwards, as always: for every canvas pixel work out where in the frame it came from. Forward
 * mapping would leave a lattice of holes wherever the transform stretches, and filling those in
 * afterwards costs more than sampling correctly in the first place.
 */
object FrameWarper {

    /**
     * Draws [frame] into a canvas-sized buffer using [placement], returning the image and the mask
     * of where it actually landed.
     *
     * [frameCoverage] carries the cylindrical projection's own empty corners through, so they never
     * get blended in as black.
     */
    fun warp(
        frame: FloatImage,
        frameCoverage: FloatImage,
        placement: Similarity,
        canvasWidth: Int,
        canvasHeight: Int,
    ): PlacedFrame {
        val image = FloatImage(canvasWidth, canvasHeight, RGB)
        val coverage = FloatImage(canvasWidth, canvasHeight, 1)
        val inverse = placement.invert()

        for (y in 0 until canvasHeight) {
            for (x in 0 until canvasWidth) {
                val cx = x + 0.5f
                val cy = y + 0.5f
                val sourceX = inverse.mapX(cx, cy) - 0.5f
                val sourceY = inverse.mapY(cx, cy) - 0.5f

                val index = y * canvasWidth + x
                if (sourceX < 0f || sourceY < 0f ||
                    sourceX > frame.width - 1f || sourceY > frame.height - 1f
                ) {
                    coverage.data[index] = 0f
                    continue
                }

                // A partially covered source pixel is treated as uncovered: half-covered edges are
                // exactly where the projection's black corners would otherwise bleed in.
                if (sampleMask(frameCoverage, sourceX, sourceY) < 0.99f) {
                    coverage.data[index] = 0f
                    continue
                }

                coverage.data[index] = 1f
                sampleBilinear(frame, sourceX, sourceY, image, image.offset(x, y))
            }
        }
        return PlacedFrame(image, coverage)
    }

    private fun sampleMask(mask: FloatImage, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, mask.width - 1)
        val y0 = y.toInt().coerceIn(0, mask.height - 1)
        val x1 = (x0 + 1).coerceAtMost(mask.width - 1)
        val y1 = (y0 + 1).coerceAtMost(mask.height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = mask[x0, y0, 0] * (1 - fx) + mask[x1, y0, 0] * fx
        val bottom = mask[x0, y1, 0] * (1 - fx) + mask[x1, y1, 0] * fx
        return top * (1 - fy) + bottom * fy
    }

    private fun sampleBilinear(source: FloatImage, x: Float, y: Float, out: FloatImage, target: Int) {
        val x0 = x.toInt().coerceIn(0, source.width - 1)
        val y0 = y.toInt().coerceIn(0, source.height - 1)
        val x1 = (x0 + 1).coerceAtMost(source.width - 1)
        val y1 = (y0 + 1).coerceAtMost(source.height - 1)
        val fx = x - x0
        val fy = y - y0

        for (c in 0 until source.channels) {
            val top = source[x0, y0, c] * (1 - fx) + source[x1, y0, c] * fx
            val bottom = source[x0, y1, c] * (1 - fx) + source[x1, y1, c] * fx
            out.data[target + c] = top * (1 - fy) + bottom * fy
        }
    }

    private const val RGB = 3
}

/** One frame already resampled into canvas space, with the mask saying where it has pixels. */
class PlacedFrame(val image: FloatImage, val coverage: FloatImage) {
    init {
        require(image.sameSizeAs(coverage)) { "Coverage must match the placed frame" }
        require(coverage.channels == 1) { "Coverage is a single-channel mask" }
    }
}
