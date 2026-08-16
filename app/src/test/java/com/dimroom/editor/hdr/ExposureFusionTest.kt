package com.dimroom.editor.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExposureFusionTest {

    @Test
    fun `normalised weights sum to one at every pixel`() {
        val bracket = listOf(gradient(exposure = 0.4f), gradient(exposure = 1f), gradient(exposure = 2.2f))

        val weights = ExposureFusion.normalisedWeights(bracket)

        for (pixel in 0 until bracket.first().pixelCount) {
            val total = weights.sumOf { it.data[pixel].toDouble() }.toFloat()
            assertEquals("Pixel $pixel weights did not sum to 1", 1f, total, 1e-3f)
        }
    }

    @Test
    fun `a pixel no frame likes still gets a defined split`() {
        // Pure black everywhere scores zero on contrast and saturation in every frame.
        val black = { FloatImage(8, 8, 3, FloatArray(8 * 8 * 3)) }
        val weights = ExposureFusion.normalisedWeights(listOf(black(), black()))

        for (pixel in 0 until 64) {
            assertEquals(1f, weights[0].data[pixel] + weights[1].data[pixel], 1e-4f)
        }
    }

    @Test
    fun `fusing identical frames returns that frame`() {
        val source = gradient(exposure = 1f)

        val fused = ExposureFusion.fuse(listOf(source, source, source))

        assertEquals(source.width, fused.width)
        assertEquals(source.height, fused.height)
        var worst = 0f
        for (i in source.data.indices) worst = maxOf(worst, abs(source.data[i] - fused.data[i]))
        assertTrue("Fusing identical frames drifted by $worst", worst < 5e-3f)
    }

    @Test
    fun `fusing a single frame is a no-op`() {
        val source = gradient(exposure = 1.5f)

        val fused = ExposureFusion.fuse(listOf(source))

        for (i in source.data.indices) {
            assertEquals(source.data[i].coerceIn(0f, 1f), fused.data[i], 1e-6f)
        }
    }

    @Test
    fun `fusion recovers detail that each single exposure loses`() {
        // A scene whose left half is very dark and right half very bright. The dark frame blows out
        // the right, the bright frame crushes the left; only the fusion should hold both.
        val width = 48
        val height = 32
        val scene = FloatImage(width, height, 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Fine checkerboard detail so there is real contrast to preserve.
                val detail = if ((x / 2 + y / 2) % 2 == 0) 1.15f else 0.85f
                val base = if (x < width / 2) 0.06f else 0.9f
                val value = (base * detail).coerceIn(0f, 1f)
                for (c in 0 until 3) scene[x, y, c] = value
            }
        }

        val dark = expose(scene, 0.35f)
        val bright = expose(scene, 3.5f)
        val fused = ExposureFusion.fuse(listOf(dark, bright))

        val darkSideContrast = localContrast(fused, 0, width / 2)
        val brightSideContrast = localContrast(fused, width / 2, width)

        // The bright frame has clipped the right half to flat white; the dark frame has crushed the
        // left half to near black. The fused image should keep measurable structure in both.
        assertTrue(
            "Shadow detail lost: contrast $darkSideContrast",
            darkSideContrast > localContrast(bright, 0, width / 2),
        )
        assertTrue(
            "Highlight detail lost: contrast $brightSideContrast",
            brightSideContrast > localContrast(dark, width / 2, width),
        )
    }

    @Test
    fun `output stays inside the displayable range`() {
        val fused = ExposureFusion.fuse(
            listOf(gradient(exposure = 0.2f), gradient(exposure = 4f)),
        )

        fused.data.forEach {
            assertTrue("Sample $it escaped 0..1", it in 0f..1f)
        }
    }

    @Test
    fun `mismatched sizes are rejected rather than producing garbage`() {
        val small = gradient(exposure = 1f, width = 16, height = 16)
        val large = gradient(exposure = 1f, width = 32, height = 32)

        val error = runCatching { ExposureFusion.fuse(listOf(small, large)) }.exceptionOrNull()

        assertTrue("Expected a size complaint, got $error", error is IllegalArgumentException)
    }

    /** Mean absolute difference between neighbouring pixels, a stand-in for retained detail. */
    private fun localContrast(image: FloatImage, fromX: Int, toX: Int): Float {
        var total = 0f
        var count = 0
        for (y in 0 until image.height) {
            for (x in fromX until toX - 1) {
                total += abs(image[x, y, 0] - image[x + 1, y, 0])
                count++
            }
        }
        return if (count == 0) 0f else total / count
    }

    private fun expose(scene: FloatImage, stops: Float): FloatImage {
        val out = FloatImage(scene.width, scene.height, scene.channels)
        for (i in scene.data.indices) out.data[i] = (scene.data[i] * stops).coerceIn(0f, 1f)
        return out
    }

    private fun gradient(exposure: Float, width: Int = 32, height: Int = 24): FloatImage {
        val image = FloatImage(width, height, 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image[x, y, 0] = (x.toFloat() / width * exposure).coerceIn(0f, 1f)
                image[x, y, 1] = (y.toFloat() / height * exposure).coerceIn(0f, 1f)
                image[x, y, 2] = ((x + y).toFloat() / (width + height) * exposure).coerceIn(0f, 1f)
            }
        }
        return image
    }
}
