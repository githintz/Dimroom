package com.dimroom.editor.pano

import com.dimroom.editor.hdr.FloatImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaGeometryTest {

    // -- Cylindrical projection -----------------------------------------------------------------

    @Test
    fun `a very long lens projects almost to identity`() {
        // As focal length grows the cylinder flattens, so this is the sanity check that the
        // projection is not introducing a scale or offset of its own.
        val width = 64
        val height = 48
        val focal = width * 50f

        val (outWidth, outHeight) = CylindricalProjection.projectedSize(width, height, focal)

        assertEquals(width.toFloat(), outWidth.toFloat(), 2f)
        assertEquals(height.toFloat(), outHeight.toFloat(), 2f)
    }

    @Test
    fun `a wide lens compresses the frame horizontally`() {
        val width = 400
        val height = 300

        val wide = CylindricalProjection.projectedSize(width, height, width * 0.5f).first
        val narrow = CylindricalProjection.projectedSize(width, height, width * 4f).first

        assertTrue("Wide lens should compress more: $wide vs $narrow", wide < narrow)
        assertTrue("Projection must not exceed the source width", narrow <= width)
    }

    @Test
    fun `warping a flat field leaves it flat where it is covered`() {
        val source = FloatImage(80, 60, 3, FloatArray(80 * 60 * 3) { 0.5f })
        val focal = 80 * 1.2f

        val warped = CylindricalProjection.warp(source, focal)
        val coverage = CylindricalProjection.coverage(80, 60, focal)

        var checked = 0
        for (pixel in 0 until warped.pixelCount) {
            if (coverage.data[pixel] < 0.5f) continue
            assertEquals(0.5f, warped.data[pixel * 3], 1e-3f)
            checked++
        }
        assertTrue("Projection covered nothing", checked > 100)
    }

    @Test
    fun `focal length from a 35mm equivalent matches the field of view`() {
        // A 26 mm equivalent is the classic phone main camera, around 70 degrees horizontally.
        val focal = CylindricalProjection.focalPixelsFrom35mm(26f, imageWidth = 4000)
        val fov = CylindricalProjection.horizontalFovDegrees(focal, 4000)

        assertEquals(70f, fov, 3f)
    }

    @Test
    fun `implausible focal lengths are rejected`() {
        assertTrue(CylindricalProjection.isPlausibleFocal(1000f, 1000))
        assertTrue(!CylindricalProjection.isPlausibleFocal(10f, 1000))
        assertTrue(!CylindricalProjection.isPlausibleFocal(90_000f, 1000))
    }

    // -- Chaining and layout --------------------------------------------------------------------

    @Test
    fun `chaining anchors the middle frame and places the rest around it`() {
        // Three frames each stepping 100 px right: pairwise[i] maps frame i+1 into frame i.
        val step = Similarity.translation(-100f, 0f)
        val absolute = PanoramaCanvas.chain(listOf(step, step))

        assertEquals(3, absolute.size)
        // The middle frame is the anchor, so it stays put.
        assertEquals(0f, absolute[1].tx, 1e-3f)
        // Its neighbours land symmetrically either side of it.
        assertEquals(100f, absolute[0].tx, 1e-2f)
        assertEquals(-100f, absolute[2].tx, 1e-2f)
    }

    @Test
    fun `chaining a single pair still produces two placements`() {
        val absolute = PanoramaCanvas.chain(listOf(Similarity.translation(-50f, 10f)))

        assertEquals(2, absolute.size)
        val dx = absolute[0].tx - absolute[1].tx
        assertEquals(50f, dx, 1e-2f)
    }

    @Test
    fun `layout covers every frame and starts at the origin`() {
        val transforms = listOf(
            Similarity.translation(0f, 0f),
            Similarity.translation(80f, 10f),
        )
        val sizes = listOf(100 to 100, 100 to 100)

        val layout = PanoramaCanvas.layout(transforms, sizes, maxPixels = 10_000_000)

        assertEquals(1f, layout.scale, 1e-4f)
        assertEquals(180, layout.width)
        assertEquals(110, layout.height)
        // Everything is shifted so the leftmost, topmost frame corner sits at the origin.
        assertEquals(0f, layout.placements[0].tx, 1e-3f)
        assertEquals(0f, layout.placements[0].ty, 1e-3f)
    }

    @Test
    fun `layout shrinks to fit a pixel budget`() {
        val transforms = listOf(Similarity.translation(0f, 0f), Similarity.translation(3000f, 0f))
        val sizes = listOf(2000 to 1500, 2000 to 1500)

        val layout = PanoramaCanvas.layout(transforms, sizes, maxPixels = 1_000_000)

        assertTrue(
            "Canvas of ${layout.width}x${layout.height} exceeded the budget",
            layout.width.toLong() * layout.height <= 1_100_000,
        )
        assertTrue("Expected a downscale, got ${layout.scale}", layout.scale < 1f)
        // Aspect ratio has to survive the fit, or the panorama comes out stretched.
        val rawAspect = 5000f / 1500f
        assertEquals(rawAspect, layout.width.toFloat() / layout.height, 0.05f)
    }

    // -- Blending -------------------------------------------------------------------------------

    @Test
    fun `edge distance weights peak away from the frame border`() {
        val coverage = FloatImage(40, 40, 1, FloatArray(40 * 40) { 1f })

        val weights = PanoramaBlender.edgeDistanceWeights(coverage)

        val centre = weights[20, 20, 0]
        val edge = weights[0, 20, 0]
        assertTrue("Centre should outweigh the edge: $centre vs $edge", centre > edge)
        assertEquals(0f, edge, 1e-4f)
    }

    @Test
    fun `uncovered pixels carry no weight`() {
        val coverage = FloatImage(20, 20, 1)
        for (y in 5 until 15) {
            for (x in 5 until 15) coverage[x, y, 0] = 1f
        }

        val weights = PanoramaBlender.edgeDistanceWeights(coverage)

        assertEquals(0f, weights[0, 0, 0], 1e-6f)
        assertTrue(weights[10, 10, 0] > 0f)
    }

    @Test
    fun `overlap gain recovers a known exposure difference`() {
        val bright = FloatImage(30, 30, 3, FloatArray(30 * 30 * 3) { 0.6f })
        val dark = FloatImage(30, 30, 3, FloatArray(30 * 30 * 3) { 0.3f })
        val covered = FloatImage(30, 30, 1, FloatArray(30 * 30) { 1f })

        val gain = PanoramaBlender.overlapGain(bright, covered, dark, covered)

        assertEquals(2f, gain!!, 1e-3f)
    }

    @Test
    fun `overlap gain declines to guess when frames barely meet`() {
        val image = FloatImage(30, 30, 3, FloatArray(30 * 30 * 3) { 0.5f })
        val covered = FloatImage(30, 30, 1, FloatArray(30 * 30) { 1f })
        val barely = FloatImage(30, 30, 1)
        barely[0, 0, 0] = 1f

        assertNull(PanoramaBlender.overlapGain(image, covered, image, barely))
    }

    @Test
    fun `blending two identical frames reproduces them`() {
        val frame = FloatImage(64, 64, 3, FloatArray(64 * 64 * 3) { 0.42f })
        val covered = FloatImage(64, 64, 1, FloatArray(64 * 64) { 1f })
        val totals = FloatArray(64 * 64) { 2f }
        val weight = FloatImage(64, 64, 1, FloatArray(64 * 64) { 1f })

        val accumulator = PanoramaBlender.Accumulator(64, 64)
        accumulator.add(frame, PanoramaBlender.normaliseAgainst(weight, totals))
        accumulator.add(frame, PanoramaBlender.normaliseAgainst(weight, totals))
        val blended = accumulator.collapse()

        for (pixel in 0 until blended.pixelCount) {
            assertEquals(0.42f, blended.data[pixel * 3], 5e-3f)
        }
        // The coverage mask is what the caller passes through; assert it is still consistent.
        assertEquals(64 * 64, covered.pixelCount)
    }
}
