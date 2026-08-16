package com.dimroom.editor.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The pyramid is the part of fusion that silently ruins output when it is slightly wrong: a
 * mismatched expand size or a mis-scaled kernel shows up as soft haloing rather than an exception.
 * These tests pin the invariants that guarantee it is lossless.
 */
class PyramidsTest {

    @Test
    fun `reduce halves the dimensions rounding up`() {
        val reduced = Pyramids.reduce(noise(width = 65, height = 33))

        assertEquals(33, reduced.width)
        assertEquals(17, reduced.height)
    }

    @Test
    fun `expand restores the exact requested size`() {
        val source = noise(width = 17, height = 9)

        val expanded = Pyramids.expand(source, 33, 17)

        assertEquals(33, expanded.width)
        assertEquals(17, expanded.height)
    }

    @Test
    fun `laplacian pyramid collapses back to the original`() {
        val source = noise(width = 64, height = 48)
        val levels = Pyramids.levelsFor(source.width, source.height)

        val restored = Pyramids.collapse(Pyramids.laplacianPyramid(source, levels))

        assertEquals(source.width, restored.width)
        assertEquals(source.height, restored.height)
        assertMaxDifference(source, restored, tolerance = 1e-3f)
    }

    @Test
    fun `laplacian pyramid round trips at odd sizes too`() {
        // Odd dimensions are where an off-by-one in expand would show up.
        val source = noise(width = 53, height = 37)
        val levels = Pyramids.levelsFor(source.width, source.height)

        val restored = Pyramids.collapse(Pyramids.laplacianPyramid(source, levels))

        assertMaxDifference(source, restored, tolerance = 1e-3f)
    }

    @Test
    fun `single channel images round trip`() {
        val source = noise(width = 40, height = 40, channels = 1)
        val levels = Pyramids.levelsFor(source.width, source.height)

        val restored = Pyramids.collapse(Pyramids.laplacianPyramid(source, levels))

        assertMaxDifference(source, restored, tolerance = 1e-3f)
    }

    @Test
    fun `reduce preserves a flat field`() {
        val flat = FloatImage(32, 32, 3, FloatArray(32 * 32 * 3) { 0.4f })

        val reduced = Pyramids.reduce(flat)

        reduced.data.forEach { assertEquals(0.4f, it, 1e-4f) }
    }

    @Test
    fun `expand preserves a flat field`() {
        val flat = FloatImage(16, 16, 3, FloatArray(16 * 16 * 3) { 0.6f })

        val expanded = Pyramids.expand(flat, 32, 32)

        // Interior samples only: the border taps clamp and are allowed to differ slightly.
        for (y in 4 until 28) {
            for (x in 4 until 28) {
                assertEquals(0.6f, expanded[x, y, 0], 1e-3f)
            }
        }
    }

    @Test
    fun `level count shrinks with the image and never reaches zero`() {
        assertTrue(Pyramids.levelsFor(1024, 768) > Pyramids.levelsFor(64, 64))
        assertEquals(1, Pyramids.levelsFor(4, 4))
        assertTrue(Pyramids.levelsFor(4096, 4096, maxLevels = 5) <= 5)
    }

    private fun assertMaxDifference(expected: FloatImage, actual: FloatImage, tolerance: Float) {
        var worst = 0f
        for (i in expected.data.indices) {
            worst = maxOf(worst, abs(expected.data[i] - actual.data[i]))
        }
        assertTrue("Worst reconstruction error $worst exceeded $tolerance", worst <= tolerance)
    }

    private fun noise(width: Int, height: Int, channels: Int = 3): FloatImage {
        val random = Random(seed = width * 31 + height)
        return FloatImage(width, height, channels, FloatArray(width * height * channels) {
            random.nextFloat()
        })
    }
}
