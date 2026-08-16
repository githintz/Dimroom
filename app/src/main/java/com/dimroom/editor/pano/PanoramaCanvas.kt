package com.dimroom.editor.pano

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Where every frame lands once the chain of pairwise transforms is resolved onto one plane. */
data class CanvasLayout(
    val width: Int,
    val height: Int,
    /** Transform taking each frame's cylindrical coordinates into canvas coordinates. */
    val placements: List<Similarity>,
    /** Scale applied to fit the requested pixel budget; 1 when nothing had to shrink. */
    val scale: Float,
)

object PanoramaCanvas {

    /**
     * Chains pairwise transforms onto a common frame.
     *
     * [pairwise] holds the transform mapping frame `i+1` into frame `i`. Anchoring on the middle
     * frame rather than the first halves the worst-case accumulated error: with no bundle
     * adjustment to redistribute it, drift grows along the chain, so the shortest chain to any frame
     * is what keeps the ends from bowing.
     */
    fun chain(pairwise: List<Similarity>): List<Similarity> {
        val frameCount = pairwise.size + 1
        val anchor = frameCount / 2
        val absolute = arrayOfNulls<Similarity>(frameCount)
        absolute[anchor] = Similarity.IDENTITY

        // Walk right from the anchor, composing each step onto the running transform.
        for (i in anchor until frameCount - 1) {
            absolute[i + 1] = pairwise[i].then(absolute[i]!!)
        }
        // Walk left, inverting because pairwise[i] maps i+1 into i.
        for (i in anchor downTo 1) {
            absolute[i - 1] = pairwise[i - 1].invert().then(absolute[i]!!)
        }
        return absolute.map { it ?: Similarity.IDENTITY }
    }

    /**
     * Bounding box of every placed frame, shrunk to respect [maxPixels].
     *
     * A five-frame sweep easily exceeds any sensible bitmap, so the budget is applied here — once,
     * to the whole layout — rather than per frame, where it would not compose.
     */
    fun layout(
        transforms: List<Similarity>,
        frameSizes: List<Pair<Int, Int>>,
        maxPixels: Int,
    ): CanvasLayout {
        require(transforms.size == frameSizes.size) { "One transform per frame is required" }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        transforms.forEachIndexed { index, transform ->
            val (width, height) = frameSizes[index]
            val corners = listOf(
                0f to 0f,
                width.toFloat() to 0f,
                0f to height.toFloat(),
                width.toFloat() to height.toFloat(),
            )
            for ((cx, cy) in corners) {
                val x = transform.mapX(cx, cy)
                val y = transform.mapY(cx, cy)
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
        }

        val rawWidth = ceil(maxX - minX).toInt().coerceAtLeast(1)
        val rawHeight = ceil(maxY - minY).toInt().coerceAtLeast(1)

        val rawPixels = rawWidth.toLong() * rawHeight.toLong()
        val scale = if (rawPixels > maxPixels) {
            kotlin.math.sqrt(maxPixels.toDouble() / rawPixels).toFloat()
        } else {
            1f
        }

        // Shift so the bounding box starts at the origin, then apply the fitting scale.
        val shift = Similarity(scale, 0f, -floor(minX) * scale, -floor(minY) * scale)
        val placements = transforms.map { it.then(shift) }

        return CanvasLayout(
            width = (rawWidth * scale).toInt().coerceAtLeast(1),
            height = (rawHeight * scale).toInt().coerceAtLeast(1),
            placements = placements,
            scale = scale,
        )
    }
}
