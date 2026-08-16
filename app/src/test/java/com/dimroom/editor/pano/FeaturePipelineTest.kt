package com.dimroom.editor.pano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End-to-end over the sparse-matching half of the stitcher: detect, describe, match, fit. These are
 * the stages that decide whether two frames line up at all, and a synthetic scene with a known
 * transform is the only way to check them without real photographs.
 */
class FeaturePipelineTest {

    @Test
    fun `detects corners on textured content and none on a flat field`() {
        val textured = FeatureDetector.detect(scene())
        val flat = FeatureDetector.detect(
            GrayImage(200, 200, IntArray(200 * 200) { 128 }),
        )

        assertTrue("Expected plenty of corners, found ${textured.size}", textured.size > 40)
        assertTrue("A flat field has no corners, found ${flat.size}", flat.isEmpty())
    }

    @Test
    fun `descriptors are stable under the transform they claim to survive`() {
        val original = scene()
        val shifted = translate(original, dx = 17, dy = -11)

        val a = FeatureDetector.detect(original)
        val b = FeatureDetector.detect(shifted)
        val matches = SimilarityEstimator.match(a, b)

        assertTrue("Too few matches to work with: ${matches.size}", matches.size > 20)
    }

    @Test
    fun `recovers a pure translation between two overlapping frames`() {
        val original = scene()
        val shifted = translate(original, dx = 24, dy = -9)

        val matches = SimilarityEstimator.match(
            FeatureDetector.detect(original),
            FeatureDetector.detect(shifted),
        )
        val result = SimilarityEstimator.estimate(matches)

        assertNotNull("No transform estimated from ${matches.size} matches", result)
        assertEquals(24f, result!!.transform.tx, 1.5f)
        assertEquals(-9f, result.transform.ty, 1.5f)
        assertEquals(1f, result.transform.scale, 0.05f)
        assertTrue("Inlier ratio too low: ${result.inlierRatio}", result.inlierRatio > 0.5f)
    }

    @Test
    fun `unrelated frames do not produce a confident transform`() {
        // Guards the failure the user actually notices: two photos that do not overlap should be
        // reported as unstitchable rather than silently fitted to noise.
        val first = scene(seed = 1)
        val second = scene(seed = 999)

        val matches = SimilarityEstimator.match(
            FeatureDetector.detect(first),
            FeatureDetector.detect(second),
        )
        val result = SimilarityEstimator.estimate(matches)

        val confident = result != null && result.inliers >= 12 && result.inlierRatio > 0.5f
        assertTrue("Unrelated frames were matched confidently: $result", !confident)
    }

    /** Distinctive random-rectangle texture: plenty of corners, little repetition. */
    private fun scene(seed: Int = 42, width: Int = 220, height: Int = 220): GrayImage {
        val random = Random(seed)
        val pixels = IntArray(width * height) { 210 }
        repeat(70) {
            val w = random.nextInt(6, 22)
            val h = random.nextInt(6, 22)
            val x0 = random.nextInt(0, width - w)
            val y0 = random.nextInt(0, height - h)
            val tone = random.nextInt(20, 150)
            for (y in y0 until y0 + h) {
                for (x in x0 until x0 + w) {
                    pixels[y * width + x] = tone
                }
            }
        }
        return GrayImage(width, height, pixels)
    }

    /** Shifts content, clamping at the borders the way a real overlap would run out of scene. */
    private fun translate(image: GrayImage, dx: Int, dy: Int): GrayImage {
        val pixels = IntArray(image.pixels.size)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val sourceX = (x - dx).coerceIn(0, image.width - 1)
                val sourceY = (y - dy).coerceIn(0, image.height - 1)
                pixels[y * image.width + x] = image.pixels[sourceY * image.width + sourceX]
            }
        }
        return GrayImage(image.width, image.height, pixels)
    }
}
