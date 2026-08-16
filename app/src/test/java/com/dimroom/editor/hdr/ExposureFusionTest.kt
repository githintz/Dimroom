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
    fun `fusion holds detail in both halves that no single exposure holds`() {
        // A scene whose left half sits deep in shadow and right half near clipping. The long
        // exposure blows out the right, the short one crushes the left.
        val width = 48
        val scene = brackedScene(width = width, monochrome = false)
        val dark = expose(scene, 0.35f)
        val bright = expose(scene, 3.5f)

        val fused = ExposureFusion.fuse(listOf(dark, bright))

        val darkShadows = localContrast(dark, 0, width / 2)
        val brightShadows = localContrast(bright, 0, width / 2)
        val darkHighlights = localContrast(dark, width / 2, width)
        val brightHighlights = localContrast(bright, width / 2, width)

        // Precondition: neither single frame holds both ends of the scene.
        assertTrue("Test scene does not crush shadows", darkShadows < brightShadows / 4f)
        assertTrue("Test scene does not clip highlights", brightHighlights < darkHighlights / 4f)

        // Fusion selects the better-exposed frame per region; it does not invent contrast beyond
        // what that frame recorded, so the bar is "keeps most of it", not "exceeds it".
        val fusedShadows = localContrast(fused, 0, width / 2)
        val fusedHighlights = localContrast(fused, width / 2, width)
        assertTrue(
            "Shadow detail lost: fused $fusedShadows vs best single frame $brightShadows",
            fusedShadows > brightShadows * 0.5f,
        )
        assertTrue(
            "Highlight detail lost: fused $fusedHighlights vs best single frame $darkHighlights",
            fusedHighlights > darkHighlights * 0.5f,
        )
    }

    @Test
    fun `a monochrome bracket still fuses by contrast and exposure`() {
        // Saturation is identically zero in every frame of a black-and-white bracket. A weight that
        // multiplied the raw terms would collapse to a constant, normalise to an even split, and
        // silently degrade fusion into a plain average.
        val width = 48
        val scene = brackedScene(width = width, monochrome = true)
        val dark = expose(scene, 0.35f)
        val bright = expose(scene, 3.5f)

        val fused = ExposureFusion.fuse(listOf(dark, bright))

        val fusedShadows = localContrast(fused, 0, width / 2)
        val brightShadows = localContrast(bright, 0, width / 2)
        val average = localContrast(averageOf(dark, bright), 0, width / 2)

        assertTrue(
            "Monochrome shadows lost: fused $fusedShadows vs well-exposed frame $brightShadows",
            fusedShadows > brightShadows * 0.5f,
        )
        assertTrue(
            "Fusion is no better than a plain average: $fusedShadows vs $average",
            fusedShadows > average * 1.2f,
        )
    }

    /** High-contrast test scene: deep shadow on the left, near-clipping on the right. */
    private fun brackedScene(width: Int, monochrome: Boolean, height: Int = 32): FloatImage {
        val scene = FloatImage(width, height, 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Fine checkerboard so there is real local contrast to preserve or lose.
                val detail = if ((x / 2 + y / 2) % 2 == 0) 1.15f else 0.85f
                val base = if (x < width / 2) {
                    if (monochrome) floatArrayOf(0.06f, 0.06f, 0.06f) else floatArrayOf(0.08f, 0.05f, 0.03f)
                } else {
                    if (monochrome) floatArrayOf(0.92f, 0.92f, 0.92f) else floatArrayOf(0.95f, 0.88f, 0.80f)
                }
                for (c in 0 until 3) scene[x, y, c] = (base[c] * detail).coerceIn(0f, 1f)
            }
        }
        return scene
    }

    private fun averageOf(first: FloatImage, second: FloatImage): FloatImage {
        val out = FloatImage(first.width, first.height, first.channels)
        for (i in out.data.indices) out.data[i] = (first.data[i] + second.data[i]) / 2f
        return out
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
