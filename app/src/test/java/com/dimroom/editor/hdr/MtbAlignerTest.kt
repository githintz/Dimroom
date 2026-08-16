package com.dimroom.editor.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class MtbAlignerTest {

    @Test
    fun `identical frames need no shift`() {
        val image = texture()

        val offset = MtbAligner.align(image, image)

        assertTrue("Expected no shift, got $offset", offset.isZero)
    }

    @Test
    fun `recovers a known translation`() {
        val reference = texture()
        val shifted = shift(reference, dx = 5, dy = -3)

        val offset = MtbAligner.align(reference, shifted)

        // Aligning the shifted frame back onto the reference is the inverse of the shift applied.
        assertEquals(-5, offset.dx)
        assertEquals(3, offset.dy)
    }

    @Test
    fun `alignment survives a large exposure difference`() {
        // This is the whole point of thresholding at the median: the frames differ by nearly three
        // stops, which would defeat any direct intensity comparison.
        val reference = texture()
        val brighter = MtbAligner.GrayImage(
            reference.width,
            reference.height,
            IntArray(reference.pixels.size) { (reference.pixels[it] * 2.6).toInt().coerceAtMost(255) },
        )
        val shifted = shift(brighter, dx = -4, dy = 6)

        val offset = MtbAligner.align(reference, shifted)

        assertEquals(4, offset.dx)
        assertEquals(-6, offset.dy)
    }

    @Test
    fun `recovers shifts larger than one pyramid step`() {
        val reference = texture(width = 256, height = 256)
        val shifted = shift(reference, dx = 21, dy = -17)

        val offset = MtbAligner.align(reference, shifted, maxShiftBits = 6)

        assertEquals(-21, offset.dx)
        assertEquals(17, offset.dy)
    }

    @Test
    fun `halving averages down to the rounded up size`() {
        val image = texture(width = 65, height = 33)

        val halved = MtbAligner.halve(image)

        assertEquals(33, halved.width)
        assertEquals(17, halved.height)
    }

    @Test
    fun `mismatched sizes are rejected`() {
        val error = runCatching {
            MtbAligner.align(texture(width = 64, height = 64), texture(width = 32, height = 32))
        }.exceptionOrNull()

        assertTrue("Expected a size complaint, got $error", error is IllegalArgumentException)
    }

    /** Shifts content by ([dx], [dy]), filling the exposed edge with the clamped border. */
    private fun shift(image: MtbAligner.GrayImage, dx: Int, dy: Int): MtbAligner.GrayImage {
        val pixels = IntArray(image.pixels.size)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val sourceX = (x - dx).coerceIn(0, image.width - 1)
                val sourceY = (y - dy).coerceIn(0, image.height - 1)
                pixels[y * image.width + x] = image.pixels[sourceY * image.width + sourceX]
            }
        }
        return MtbAligner.GrayImage(image.width, image.height, pixels)
    }

    /**
     * Structured texture with a broad histogram: blobs plus noise, so the median threshold lands in
     * a meaningful place and there is real structure to match against.
     */
    private fun texture(width: Int = 128, height: Int = 128): MtbAligner.GrayImage {
        val random = Random(seed = 20260816)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val blob = if (abs((x % 37) - 18) + abs((y % 41) - 20) < 14) 170 else 60
                val ramp = (x * 40 / width) + (y * 30 / height)
                pixels[y * width + x] = (blob + ramp + random.nextInt(-12, 12)).coerceIn(0, 255)
            }
        }
        return MtbAligner.GrayImage(width, height, pixels)
    }
}
