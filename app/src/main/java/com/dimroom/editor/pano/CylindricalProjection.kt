package com.dimroom.editor.pano

import com.dimroom.editor.hdr.FloatImage
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.tan

/**
 * Projects frames onto a cylinder before anything else looks at them.
 *
 * This is the decision the whole stitcher rests on. Left in their own planes, two frames from a
 * sweep are related by a homography — eight degrees of freedom, estimated from noisy matches, and
 * chained across a sequence it accumulates drift that only bundle adjustment can take back out.
 * Wrapped onto a cylinder around the camera's optical centre, a horizontal sweep becomes very close
 * to pure translation, so the model collapses to four degrees of freedom that are far easier to
 * estimate and far harder to get badly wrong.
 *
 * The cost is an assumption: the camera rotated about its optical centre and did not travel. That is
 * what a hand-swept phone panorama approximates, and it is what this stitcher is for.
 */
object CylindricalProjection {

    /** Diagonal-agnostic 35 mm frame width, the reference for equivalent focal lengths. */
    private const val FULL_FRAME_WIDTH_MM = 36f

    /** Assumed horizontal field of view when EXIF tells us nothing. Typical of a phone main camera. */
    private const val FALLBACK_HORIZONTAL_FOV_DEGREES = 67f

    /**
     * Focal length in pixels from a 35 mm-equivalent focal length.
     *
     * Phone EXIF almost always carries `FocalLengthIn35mmFilm`, which sidesteps needing to know the
     * sensor's physical size.
     */
    fun focalPixelsFrom35mm(focal35mm: Float, imageWidth: Int): Float =
        focal35mm / FULL_FRAME_WIDTH_MM * imageWidth

    /** Focal length in pixels implied by [FALLBACK_HORIZONTAL_FOV_DEGREES]. */
    fun fallbackFocalPixels(imageWidth: Int): Float {
        val halfFov = Math.toRadians(FALLBACK_HORIZONTAL_FOV_DEGREES / 2.0)
        return (imageWidth / 2f) / tan(halfFov).toFloat()
    }

    /** Size of the cylindrical image produced for a source of these dimensions. */
    fun projectedSize(width: Int, height: Int, focalPixels: Float): Pair<Int, Int> {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        // Width compresses as rays fan out; height is measured at the centre column, where the
        // cylinder touches the image plane and the scale is exactly 1.
        val outHalfWidth = focalPixels * atan(halfWidth / focalPixels)
        return ceil(outHalfWidth * 2).toInt().coerceAtLeast(1) to
            ceil(halfHeight * 2).toInt().coerceAtLeast(1)
    }

    /**
     * Warps [source] onto the cylinder.
     *
     * Resampling runs backwards — for every destination pixel, work out where it came from — because
     * forward mapping leaves holes wherever the projection stretches.
     */
    fun warp(source: FloatImage, focalPixels: Float): FloatImage {
        val (outWidth, outHeight) = projectedSize(source.width, source.height, focalPixels)
        val out = FloatImage(outWidth, outHeight, source.channels)

        val sourceCx = source.width / 2f
        val sourceCy = source.height / 2f
        val outCx = outWidth / 2f
        val outCy = outHeight / 2f

        for (y in 0 until outHeight) {
            val normalisedY = (y + 0.5f - outCy) / focalPixels
            for (x in 0 until outWidth) {
                val theta = (x + 0.5f - outCx) / focalPixels
                val tanTheta = tan(theta)
                val sourceX = focalPixels * tanTheta + sourceCx
                // Vertical scale grows with the angle off-axis, by exactly 1/cos(theta).
                val sourceY = normalisedY * focalPixels / cos(theta) + sourceCy
                sampleBilinear(source, sourceX, sourceY, out, out.offset(x, y))
            }
        }
        return out
    }

    /**
     * Coverage mask for a warped frame: 1 inside the projected image, 0 in the corners the
     * projection leaves empty. Blending needs this to avoid dragging black wedges into the seam.
     */
    fun coverage(sourceWidth: Int, sourceHeight: Int, focalPixels: Float): FloatImage {
        val (outWidth, outHeight) = projectedSize(sourceWidth, sourceHeight, focalPixels)
        val mask = FloatImage(outWidth, outHeight, 1)

        val sourceCx = sourceWidth / 2f
        val sourceCy = sourceHeight / 2f
        val outCx = outWidth / 2f
        val outCy = outHeight / 2f

        for (y in 0 until outHeight) {
            val normalisedY = (y + 0.5f - outCy) / focalPixels
            for (x in 0 until outWidth) {
                val theta = (x + 0.5f - outCx) / focalPixels
                val sourceX = focalPixels * tan(theta) + sourceCx
                val sourceY = normalisedY * focalPixels / cos(theta) + sourceCy
                val inside = sourceX >= 0f && sourceX <= sourceWidth - 1f &&
                    sourceY >= 0f && sourceY <= sourceHeight - 1f
                mask.data[y * outWidth + x] = if (inside) 1f else 0f
            }
        }
        return mask
    }

    private fun sampleBilinear(source: FloatImage, x: Float, y: Float, out: FloatImage, target: Int) {
        if (x < 0f || y < 0f || x > source.width - 1f || y > source.height - 1f) {
            for (c in 0 until source.channels) out.data[target + c] = 0f
            return
        }
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

    /** Great-circle-ish sanity check used by callers to reject an implausible focal length. */
    fun isPlausibleFocal(focalPixels: Float, imageWidth: Int): Boolean =
        focalPixels > imageWidth * 0.3f && focalPixels < imageWidth * 20f

    /** Horizontal field of view a focal length implies, for reporting and diagnostics. */
    fun horizontalFovDegrees(focalPixels: Float, imageWidth: Int): Float =
        (2.0 * atan(imageWidth / 2.0 / focalPixels) * 180.0 / Math.PI).toFloat()
}
