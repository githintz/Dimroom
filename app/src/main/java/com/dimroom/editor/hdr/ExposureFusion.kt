package com.dimroom.editor.hdr

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mertens–Kautz–Van Reeth exposure fusion.
 *
 * Rather than recovering a radiance map and tone mapping it back down, this picks the best-looking
 * pixels out of the bracket directly: each frame is scored for local contrast, colour saturation and
 * how well exposed it is, and the frames are then blended band by band over a Laplacian pyramid.
 *
 * That choice matters on a phone. Radiance-map HDR needs trustworthy exposure times from EXIF and a
 * recovered camera response curve, and it still has to be tone mapped afterwards. Fusion needs
 * neither, degrades gracefully when the bracket is uneven, and hands back an ordinary displayable
 * image that flows straight into Dimroom's existing non-destructive edit pipeline.
 */
object ExposureFusion {

    /**
     * Relative influence of each quality term, as exponents on the weight product.
     * Raising one above 1 sharpens its preference; dropping it to 0 removes the term entirely.
     */
    data class Params(
        val contrastPower: Float = 1f,
        val saturationPower: Float = 1f,
        val exposurePower: Float = 1f,
        /** Width of the well-exposedness bell around mid-grey. */
        val exposureSigma: Float = 0.2f,
        val maxLevels: Int = 8,
    )

    /**
     * Fuses [images] into one image of the same size.
     *
     * All inputs must share dimensions and have three channels; the caller is responsible for
     * scaling and aligning beforehand.
     */
    fun fuse(images: List<FloatImage>, params: Params = Params()): FloatImage {
        require(images.isNotEmpty()) { "Nothing to fuse" }
        val first = images.first()
        require(images.all { it.sameSizeAs(first) }) { "All exposures must be the same size" }
        require(images.all { it.channels == RGB }) { "Exposure fusion expects three-channel images" }

        if (images.size == 1) return first.copy().clampInPlace()

        val weights = normalisedWeights(images, params)
        val levels = Pyramids.levelsFor(first.width, first.height, params.maxLevels)

        // Accumulate straight into the result pyramid so only one frame's pyramids are alive at a
        // time; holding every frame's pyramid at once is what makes naive implementations OOM.
        var result: MutableList<FloatImage>? = null
        for (index in images.indices) {
            val laplacian = Pyramids.laplacianPyramid(images[index], levels)
            val weightPyramid = Pyramids.gaussianPyramid(weights[index], levels)
            val accumulator = result ?: MutableList(levels) { level ->
                FloatImage(laplacian[level].width, laplacian[level].height, RGB)
            }.also { result = it }

            for (level in 0 until levels) {
                val band = laplacian[level]
                val weight = weightPyramid[level]
                val target = accumulator[level]
                for (pixel in 0 until band.pixelCount) {
                    val w = weight.data[pixel]
                    val base = pixel * RGB
                    target.data[base] += w * band.data[base]
                    target.data[base + 1] += w * band.data[base + 1]
                    target.data[base + 2] += w * band.data[base + 2]
                }
            }
        }

        return Pyramids.collapse(checkNotNull(result)).clampInPlace()
    }

    /**
     * Per-frame quality maps, normalised so that for every pixel the weights across the bracket sum
     * to one. Without that normalisation the fused result drifts in overall brightness.
     */
    fun normalisedWeights(images: List<FloatImage>, params: Params = Params()): List<FloatImage> {
        val maps = images.map { weightMap(it, params) }
        val pixels = images.first().pixelCount
        val totals = FloatArray(pixels)
        for (map in maps) {
            for (pixel in 0 until pixels) totals[pixel] += map.data[pixel]
        }
        for (map in maps) {
            for (pixel in 0 until pixels) {
                val total = totals[pixel]
                // A pixel that no frame likes (pure black in every exposure, say) falls back to an
                // even split rather than dividing by zero.
                map.data[pixel] = if (total > EPSILON) map.data[pixel] / total else 1f / maps.size
            }
        }
        return maps
    }

    /** Single-channel quality map: contrast x saturation x well-exposedness. */
    fun weightMap(image: FloatImage, params: Params = Params()): FloatImage {
        val map = FloatImage(image.width, image.height, 1)
        val exposureDenominator = 2f * params.exposureSigma * params.exposureSigma

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val base = image.offset(x, y)
                val r = image.data[base]
                val g = image.data[base + 1]
                val b = image.data[base + 2]

                val contrast = abs(laplacianAt(image, x, y))

                val mean = (r + g + b) / 3f
                val saturation = sqrt(
                    ((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f,
                )

                val exposedness = wellExposed(r, exposureDenominator) *
                    wellExposed(g, exposureDenominator) *
                    wellExposed(b, exposureDenominator)

                var weight = 1f
                if (params.contrastPower != 0f) weight *= contrast.pow(params.contrastPower)
                if (params.saturationPower != 0f) weight *= saturation.pow(params.saturationPower)
                if (params.exposurePower != 0f) weight *= exposedness.pow(params.exposurePower)

                // Keep every frame marginally in play so flat regions still get a defined blend.
                map.data[y * image.width + x] = weight + EPSILON
            }
        }
        return map
    }

    private fun wellExposed(value: Float, denominator: Float): Float {
        val delta = value - 0.5f
        return exp(-(delta * delta) / denominator)
    }

    /** Laplacian of the luminance channel, the local-contrast term. */
    private fun laplacianAt(image: FloatImage, x: Int, y: Int): Float =
        luminance(image, x, y - 1) +
            luminance(image, x, y + 1) +
            luminance(image, x - 1, y) +
            luminance(image, x + 1, y) -
            4f * luminance(image, x, y)

    private fun luminance(image: FloatImage, x: Int, y: Int): Float =
        0.299f * image.clamped(x, y, 0) +
            0.587f * image.clamped(x, y, 1) +
            0.114f * image.clamped(x, y, 2)

    private const val RGB = 3
    private const val EPSILON = 1e-12f
}
