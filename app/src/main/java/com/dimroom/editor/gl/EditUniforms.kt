package com.dimroom.editor.gl

import com.dimroom.domain.model.ColorBand
import com.dimroom.domain.model.EditStack
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Translates an [EditStack] — stored in user-facing Lightroom units — into shader uniforms, and
 * derives the geometry matrices for crop, rotation, straighten and flips.
 *
 * Preview and export share this object, so the exported pixels come from exactly the same numbers
 * the user saw while dragging sliders.
 */
object EditUniforms {

    /** Uploads every adjustment uniform for [stack]. */
    fun apply(
        program: GlProgram,
        stack: EditStack,
        imageWidth: Int,
        imageHeight: Int,
        splitPx: Float,
        showOriginal: Boolean,
        grainSeed: Float,
    ) {
        program.setVec2(
            "uTexelSize",
            1f / imageWidth.coerceAtLeast(1),
            1f / imageHeight.coerceAtLeast(1),
        )

        val light = stack.light
        program.setFloat("uExposure", light.exposure.coerceIn(-5f, 5f))
        program.setFloat("uContrast", light.contrast.asUnit())
        program.setFloat("uHighlights", light.highlights.asUnit())
        program.setFloat("uShadows", light.shadows.asUnit())
        program.setFloat("uWhites", light.whites.asUnit())
        program.setFloat("uBlacks", light.blacks.asUnit())

        val color = stack.color
        program.setFloat("uTemperature", color.temperature.asUnit())
        program.setFloat("uTint", color.tint.asUnit())
        program.setFloat("uVibrance", color.vibrance.asUnit())
        program.setFloat("uSaturation", color.saturation.asUnit())

        val bands = FloatArray(ColorBand.entries.size * 3)
        val centers = FloatArray(ColorBand.entries.size)
        var anyBandActive = false
        ColorBand.entries.forEachIndexed { index, band ->
            val adjustment = stack.hsl[band]
            bands[index * 3] = adjustment.hue.asUnit()
            bands[index * 3 + 1] = adjustment.saturation.asUnit()
            bands[index * 3 + 2] = adjustment.luminance.asUnit()
            centers[index] = band.centerHueDegrees
            if (!adjustment.isIdentity) anyBandActive = true
        }
        program.setVec3Array("uHsl", bands)
        program.setFloatArray("uHslCenters", centers)
        program.setFloat("uHslActive", if (anyBandActive) 1f else 0f)

        val effects = stack.effects
        program.setFloat("uClarity", effects.clarity.asUnit())
        program.setFloat("uDehaze", effects.dehaze.asUnit())
        program.setFloat("uVignette", effects.vignette.asUnit())
        program.setFloat("uGrain", (effects.grain / 100f).coerceIn(0f, 1f))
        program.setFloat("uGrainSeed", grainSeed)

        program.setFloat("uSplit", splitPx)
        program.setFloat("uShowOriginal", if (showOriginal) 1f else 0f)
    }

    /**
     * Pixel dimensions of the edited result: the crop rect, with the axes swapped for a quarter
     * turn. Used to letterbox the preview and to size the export bitmap.
     */
    fun outputSize(geometry: EditStack.Geometry, imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        val g = geometry.normalised()
        val width = (imageWidth * g.cropWidth).toInt().coerceAtLeast(1)
        val height = (imageHeight * g.cropHeight).toInt().coerceAtLeast(1)
        return if (g.rotationDegrees == 90 || g.rotationDegrees == 270) height to width else width to height
    }

    /**
     * Maps output texture coordinates back into the source image.
     *
     * `srcUv = L * (uv - 0.5) + cropCentre`, where `L` chains the crop scale, the 90° rotation, the
     * straighten rotation and the flips. Straightening shrinks the sampled rect just enough that a
     * rotated frame never reaches past the crop, which is what stops black wedges appearing in the
     * corners.
     */
    fun textureMatrix(geometry: EditStack.Geometry, imageWidth: Int, imageHeight: Int): FloatArray {
        val g = geometry.normalised()
        val cropWidthPx = imageWidth * g.cropWidth
        val cropHeightPx = imageHeight * g.cropHeight
        val quarterTurns = g.rotationDegrees / 90

        var outWidthPx = if (quarterTurns % 2 == 0) cropWidthPx else cropHeightPx
        var outHeightPx = if (quarterTurns % 2 == 0) cropHeightPx else cropWidthPx

        val theta = Math.toRadians(g.straightenDegrees.toDouble())
        val cosT = abs(cos(theta)).toFloat()
        val sinT = abs(sin(theta)).toFloat()
        if (sinT > 1e-4f) {
            // A quarter turn swaps the output axes, so the rotated frame's footprint in *source*
            // axes is always the crop's own width and height — which makes the fit test symmetric.
            val shrink = min(
                cropWidthPx / (cropWidthPx * cosT + cropHeightPx * sinT),
                cropHeightPx / (cropWidthPx * sinT + cropHeightPx * cosT),
            )
            outWidthPx *= shrink
            outHeightPx *= shrink
        }

        // Rotation composed in y-down image space: positive straighten leans the frame clockwise.
        val c = cos(theta).toFloat()
        val s = sin(theta).toFloat()
        var r00 = c
        var r01 = -s
        var r10 = s
        var r11 = c

        repeat(quarterTurns) {
            // Left-multiply by a quarter turn. In this y-down space the sign convention below is
            // what makes rotationDegrees = 90 rotate the *photo* clockwise, matching the button.
            val n00 = r10
            val n01 = r11
            val n10 = -r00
            val n11 = -r01
            r00 = n00; r01 = n01; r10 = n10; r11 = n11
        }

        if (g.flipHorizontal) {
            r00 = -r00; r01 = -r01
        }
        if (g.flipVertical) {
            r10 = -r10; r11 = -r11
        }

        // L = diag(1/imageW, 1/imageH) * R * diag(outW, outH)
        val l00 = r00 * outWidthPx / imageWidth
        val l01 = r01 * outHeightPx / imageWidth
        val l10 = r10 * outWidthPx / imageHeight
        val l11 = r11 * outHeightPx / imageHeight

        val centerX = (g.cropLeft + g.cropRight) * 0.5f
        val centerY = (g.cropTop + g.cropBottom) * 0.5f
        val tx = centerX - (l00 + l01) * 0.5f
        val ty = centerY - (l10 + l11) * 0.5f

        // Column-major mat3.
        return floatArrayOf(
            l00, l10, 0f,
            l01, l11, 0f,
            tx, ty, 1f,
        )
    }

    /**
     * Positions the quad: letterboxes [contentWidth] x [contentHeight] inside the viewport, then
     * applies user zoom and pan (both in clip space).
     */
    fun positionMatrix(
        viewportWidth: Int,
        viewportHeight: Int,
        contentWidth: Int,
        contentHeight: Int,
        zoom: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f,
    ): FloatArray {
        val viewAspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1)
        val contentAspect = contentWidth.toFloat() / contentHeight.coerceAtLeast(1)
        var scaleX = 1f
        var scaleY = 1f
        if (contentAspect > viewAspect) {
            scaleY = viewAspect / contentAspect
        } else {
            scaleX = contentAspect / viewAspect
        }
        return floatArrayOf(
            scaleX * zoom, 0f, 0f, 0f,
            0f, scaleY * zoom, 0f, 0f,
            0f, 0f, 1f, 0f,
            panX, panY, 0f, 1f,
        )
    }

    /** Identity mat4, used when rendering straight into a framebuffer sized to the output. */
    fun identityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Slider units (-100..100) to shader units (-1..1). */
    private fun Float.asUnit(): Float = (this / 100f).coerceIn(-1f, 1f)
}
