package com.dimroom.editor.pano

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** A pair of matched features, cheaper to pass around than the features themselves. */
data class Match(
    val sourceX: Float,
    val sourceY: Float,
    val targetX: Float,
    val targetY: Float,
)

/**
 * Rotation, uniform scale and translation — four degrees of freedom.
 *
 * `x' = a*x - b*y + tx`
 * `y' = b*x + a*y + ty`
 *
 * On cylindrical coordinates this is all a horizontal sweep needs: the translation carries the pan,
 * and the small rotation and scale absorb the roll and the focal-length error. Fitting a full
 * homography here would add four degrees of freedom that the data cannot constrain and that quietly
 * turn into drift.
 */
data class Similarity(val a: Float, val b: Float, val tx: Float, val ty: Float) {

    fun mapX(x: Float, y: Float): Float = a * x - b * y + tx

    fun mapY(x: Float, y: Float): Float = b * x + a * y + ty

    val scale: Float get() = sqrt(a * a + b * b)

    /** Composes with [other] so that `this(other(p))`, used to chain frames onto a reference. */
    fun then(other: Similarity): Similarity = Similarity(
        a = other.a * a - other.b * b,
        b = other.b * a + other.a * b,
        tx = other.a * tx - other.b * ty + other.tx,
        ty = other.b * tx + other.a * ty + other.ty,
    )

    fun invert(): Similarity {
        val determinant = a * a + b * b
        if (determinant < 1e-12f) return IDENTITY
        val ia = a / determinant
        val ib = -b / determinant
        return Similarity(
            a = ia,
            b = ib,
            tx = -(ia * tx - ib * ty),
            ty = -(ib * tx + ia * ty),
        )
    }

    companion object {
        val IDENTITY = Similarity(1f, 0f, 0f, 0f)

        fun translation(tx: Float, ty: Float) = Similarity(1f, 0f, tx, ty)
    }
}

/** Matches descriptors and fits a [Similarity] robustly. */
object SimilarityEstimator {

    /** Lowe's ratio: a match is only trusted if it is clearly better than the runner-up. */
    private const val RATIO_THRESHOLD = 0.8f

    private const val RANSAC_ITERATIONS = 500
    private const val INLIER_THRESHOLD_PX = 3f
    private const val MIN_MATCHES = 6

    data class Result(
        val transform: Similarity,
        val inliers: Int,
        val totalMatches: Int,
    ) {
        val inlierRatio: Float get() = if (totalMatches == 0) 0f else inliers.toFloat() / totalMatches
    }

    /**
     * Brute-force descriptor matching with a ratio test and a symmetry check.
     *
     * Both filters exist to keep RANSAC's job tractable: the ratio test drops ambiguous matches in
     * repetitive texture, and requiring agreement in both directions drops the rest of the
     * one-sided noise.
     */
    fun match(source: List<Feature>, target: List<Feature>): List<Match> {
        if (source.isEmpty() || target.isEmpty()) return emptyList()

        val forward = IntArray(source.size) { -1 }
        for (i in source.indices) {
            forward[i] = bestMatchIndex(source[i], target)
        }
        val backward = IntArray(target.size) { -1 }
        for (j in target.indices) {
            backward[j] = bestMatchIndex(target[j], source)
        }

        val matches = ArrayList<Match>()
        for (i in source.indices) {
            val j = forward[i]
            if (j < 0) continue
            if (backward[j] != i) continue
            matches.add(Match(source[i].x, source[i].y, target[j].x, target[j].y))
        }
        return matches
    }

    /** Index of the best match passing the ratio test, or -1 when nothing is convincing. */
    private fun bestMatchIndex(feature: Feature, candidates: List<Feature>): Int {
        var bestDistance = Int.MAX_VALUE
        var secondDistance = Int.MAX_VALUE
        var bestIndex = -1
        for (index in candidates.indices) {
            val distance = hammingDistance(feature.descriptor, candidates[index].descriptor)
            if (distance < bestDistance) {
                secondDistance = bestDistance
                bestDistance = distance
                bestIndex = index
            } else if (distance < secondDistance) {
                secondDistance = distance
            }
        }
        if (bestIndex < 0) return -1
        if (secondDistance == Int.MAX_VALUE) return bestIndex
        return if (bestDistance < RATIO_THRESHOLD * secondDistance) bestIndex else -1
    }

    fun hammingDistance(a: LongArray, b: LongArray): Int {
        var distance = 0
        val size = minOf(a.size, b.size)
        for (i in 0 until size) distance += java.lang.Long.bitCount(a[i] xor b[i])
        return distance
    }

    /**
     * RANSAC over [matches], refined by least squares on the inliers.
     *
     * Two correspondences determine the model exactly, so the sampling is cheap; the refit at the
     * end is what turns a model that merely agrees with two points into one that fits them all.
     */
    fun estimate(matches: List<Match>, random: Random = Random(0x5EED)): Result? {
        if (matches.size < MIN_MATCHES) return null

        var bestTransform = Similarity.IDENTITY
        var bestInliers = 0

        repeat(RANSAC_ITERATIONS) {
            val first = matches[random.nextInt(matches.size)]
            val second = matches[random.nextInt(matches.size)]
            val candidate = fromPair(first, second) ?: return@repeat

            var inliers = 0
            for (match in matches) {
                if (residual(candidate, match) < INLIER_THRESHOLD_PX) inliers++
            }
            if (inliers > bestInliers) {
                bestInliers = inliers
                bestTransform = candidate
            }
        }

        if (bestInliers < MIN_MATCHES) return null

        val inlierMatches = matches.filter { residual(bestTransform, it) < INLIER_THRESHOLD_PX }
        val refined = leastSquares(inlierMatches) ?: bestTransform
        val finalInliers = matches.count { residual(refined, it) < INLIER_THRESHOLD_PX }

        return Result(refined, finalInliers, matches.size)
    }

    fun residual(transform: Similarity, match: Match): Float {
        val dx = transform.mapX(match.sourceX, match.sourceY) - match.targetX
        val dy = transform.mapY(match.sourceX, match.sourceY) - match.targetY
        return sqrt(dx * dx + dy * dy)
    }

    /** Exact fit through two correspondences. */
    fun fromPair(first: Match, second: Match): Similarity? {
        val sdx = second.sourceX - first.sourceX
        val sdy = second.sourceY - first.sourceY
        val denominator = sdx * sdx + sdy * sdy
        // Two points on top of each other say nothing about rotation or scale.
        if (denominator < 1e-6f) return null

        val tdx = second.targetX - first.targetX
        val tdy = second.targetY - first.targetY

        val a = (sdx * tdx + sdy * tdy) / denominator
        val b = (sdx * tdy - sdy * tdx) / denominator
        val tx = first.targetX - (a * first.sourceX - b * first.sourceY)
        val ty = first.targetY - (b * first.sourceX + a * first.sourceY)
        return Similarity(a, b, tx, ty)
    }

    /**
     * Closed-form least squares for the four parameters.
     *
     * The similarity model is linear in `(a, b, tx, ty)`, so this is a plain normal-equation solve
     * rather than anything iterative.
     */
    fun leastSquares(matches: List<Match>): Similarity? {
        if (matches.size < 2) return null
        val n = matches.size.toFloat()

        var sumX = 0f
        var sumY = 0f
        var sumXp = 0f
        var sumYp = 0f
        for (m in matches) {
            sumX += m.sourceX
            sumY += m.sourceY
            sumXp += m.targetX
            sumYp += m.targetY
        }
        val meanX = sumX / n
        val meanY = sumY / n
        val meanXp = sumXp / n
        val meanYp = sumYp / n

        // Working from the centroids removes the translation, leaving a two-parameter problem.
        var sxx = 0f
        var sxy = 0f
        var normalisation = 0f
        for (m in matches) {
            val dx = m.sourceX - meanX
            val dy = m.sourceY - meanY
            val dxp = m.targetX - meanXp
            val dyp = m.targetY - meanYp
            sxx += dx * dxp + dy * dyp
            sxy += dx * dyp - dy * dxp
            normalisation += dx * dx + dy * dy
        }
        if (abs(normalisation) < 1e-9f) return null

        val a = sxx / normalisation
        val b = sxy / normalisation
        val tx = meanXp - (a * meanX - b * meanY)
        val ty = meanYp - (b * meanX + a * meanY)
        return Similarity(a, b, tx, ty)
    }
}
