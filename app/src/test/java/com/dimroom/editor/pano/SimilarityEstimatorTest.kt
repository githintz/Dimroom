package com.dimroom.editor.pano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SimilarityEstimatorTest {

    @Test
    fun `composition matches applying both transforms in order`() {
        val first = similarity(angleDegrees = 12f, scale = 1.1f, tx = 30f, ty = -8f)
        val second = similarity(angleDegrees = -5f, scale = 0.95f, tx = -12f, ty = 20f)

        val composed = first.then(second)

        // then() must mean "second applied after first" for chaining across a sweep to be correct.
        val x = 40f
        val y = 25f
        val expectedX = second.mapX(first.mapX(x, y), first.mapY(x, y))
        val expectedY = second.mapY(first.mapX(x, y), first.mapY(x, y))
        assertEquals(expectedX, composed.mapX(x, y), 1e-3f)
        assertEquals(expectedY, composed.mapY(x, y), 1e-3f)
    }

    @Test
    fun `inverse undoes the transform`() {
        val transform = similarity(angleDegrees = 21f, scale = 1.3f, tx = -44f, ty = 17f)
        val inverse = transform.invert()

        val x = 12f
        val y = -30f
        val mappedX = transform.mapX(x, y)
        val mappedY = transform.mapY(x, y)

        assertEquals(x, inverse.mapX(mappedX, mappedY), 1e-2f)
        assertEquals(y, inverse.mapY(mappedX, mappedY), 1e-2f)
    }

    @Test
    fun `two correspondences determine the model exactly`() {
        val truth = similarity(angleDegrees = 8f, scale = 1.05f, tx = 25f, ty = -14f)
        val first = correspondence(truth, 10f, 20f)
        val second = correspondence(truth, 90f, 65f)

        val estimated = SimilarityEstimator.fromPair(first, second)

        assertNotNull(estimated)
        assertEquals(truth.a, estimated!!.a, 1e-3f)
        assertEquals(truth.b, estimated.b, 1e-3f)
        assertEquals(truth.tx, estimated.tx, 1e-2f)
        assertEquals(truth.ty, estimated.ty, 1e-2f)
    }

    @Test
    fun `coincident points cannot determine rotation or scale`() {
        val truth = similarity(angleDegrees = 8f, scale = 1f, tx = 0f, ty = 0f)
        val point = correspondence(truth, 10f, 20f)

        assertNull(SimilarityEstimator.fromPair(point, point))
    }

    @Test
    fun `least squares recovers the model from noisy correspondences`() {
        val truth = similarity(angleDegrees = -6f, scale = 1.02f, tx = 40f, ty = 12f)
        val random = Random(7)
        val matches = (0 until 60).map {
            val x = random.nextFloat() * 400f
            val y = random.nextFloat() * 300f
            val exact = correspondence(truth, x, y)
            exact.copy(
                targetX = exact.targetX + (random.nextFloat() - 0.5f),
                targetY = exact.targetY + (random.nextFloat() - 0.5f),
            )
        }

        val estimated = SimilarityEstimator.leastSquares(matches)

        assertNotNull(estimated)
        assertEquals(truth.a, estimated!!.a, 5e-3f)
        assertEquals(truth.b, estimated.b, 5e-3f)
        assertEquals(truth.tx, estimated.tx, 1.5f)
        assertEquals(truth.ty, estimated.ty, 1.5f)
    }

    @Test
    fun `ransac ignores a large fraction of outliers`() {
        // Half the correspondences are nonsense, which is realistic for descriptor matching on
        // repetitive scenes. A plain least squares fit would be dragged well off the truth.
        val truth = similarity(angleDegrees = 4f, scale = 1f, tx = 55f, ty = -20f)
        val random = Random(11)
        val inliers = (0 until 40).map {
            correspondence(truth, random.nextFloat() * 400f, random.nextFloat() * 300f)
        }
        val outliers = (0 until 40).map {
            Match(
                sourceX = random.nextFloat() * 400f,
                sourceY = random.nextFloat() * 300f,
                targetX = random.nextFloat() * 400f,
                targetY = random.nextFloat() * 300f,
            )
        }

        val result = SimilarityEstimator.estimate(inliers + outliers, Random(3))

        assertNotNull(result)
        assertEquals(truth.tx, result!!.transform.tx, 1.5f)
        assertEquals(truth.ty, result.transform.ty, 1.5f)
        assertTrue("Expected to keep the honest half, got ${result.inliers}", result.inliers >= 35)
    }

    @Test
    fun `too few matches yields no estimate rather than a wrong one`() {
        val truth = similarity(angleDegrees = 0f, scale = 1f, tx = 5f, ty = 5f)
        val matches = listOf(correspondence(truth, 1f, 2f), correspondence(truth, 30f, 40f))

        assertNull(SimilarityEstimator.estimate(matches))
    }

    @Test
    fun `hamming distance counts differing bits`() {
        val a = longArrayOf(0b1011L, 0L, 0L, 0L)
        val b = longArrayOf(0b1101L, 0L, 0L, 0L)

        assertEquals(2, SimilarityEstimator.hammingDistance(a, b))
        assertEquals(0, SimilarityEstimator.hammingDistance(a, a))
    }

    private fun similarity(angleDegrees: Float, scale: Float, tx: Float, ty: Float): Similarity {
        val radians = Math.toRadians(angleDegrees.toDouble())
        return Similarity(
            a = (scale * cos(radians)).toFloat(),
            b = (scale * sin(radians)).toFloat(),
            tx = tx,
            ty = ty,
        )
    }

    private fun correspondence(transform: Similarity, x: Float, y: Float) =
        Match(x, y, transform.mapX(x, y), transform.mapY(x, y))
}
