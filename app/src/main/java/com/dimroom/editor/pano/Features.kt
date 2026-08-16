package com.dimroom.editor.pano

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** A detected keypoint and its binary description. */
class Feature(
    val x: Float,
    val y: Float,
    val score: Int,
    val angle: Float,
    /** 256-bit BRIEF descriptor packed into four longs. */
    val descriptor: LongArray,
)

/** Grayscale image as one sample per pixel, 0..255. */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) {
            "Buffer of ${pixels.size} does not match ${width}x$height"
        }
    }

    fun at(x: Int, y: Int): Int =
        pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
}

/**
 * FAST corner detection with an ORB-style oriented BRIEF descriptor.
 *
 * Corners rather than raw pixel correlation because panorama frames overlap only partially — a
 * correlation search has to be told roughly where to look, whereas sparse matches find the overlap
 * on their own. Binary descriptors because matching them is a popcount rather than a float distance,
 * which keeps a full brute-force match affordable on a phone.
 */
object FeatureDetector {

    /** Offsets of the 16-pixel Bresenham circle FAST tests, in order around the ring. */
    private val CIRCLE_X = intArrayOf(0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3, -3, -3, -2, -1)
    private val CIRCLE_Y = intArrayOf(-3, -3, -2, -1, 0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3)

    /** Contiguous ring pixels that must all be brighter or all darker to call it a corner. */
    private const val ARC_LENGTH = 9

    /**
     * Compass points that must agree before the full ring is read.
     *
     * Derived from [ARC_LENGTH], not chosen: with the four points spaced evenly around a sixteen
     * pixel ring, an arc of [ARC_LENGTH] contains at least `ARC_LENGTH / 4` of them.
     */
    private const val MIN_COMPASS_AGREEMENT = ARC_LENGTH / 4

    private const val PATCH_RADIUS = 15
    private const val DESCRIPTOR_BITS = 256

    /** Sampling pattern for BRIEF, fixed by seed so descriptors are comparable across runs. */
    private val PATTERN: IntArray = buildPattern()

    /**
     * Detects up to [maxFeatures] corners, keeping the strongest spread across the frame.
     *
     * [threshold] is the intensity difference a ring pixel must clear; lower finds more on flat
     * scenes at the cost of noise matches.
     */
    fun detect(image: GrayImage, maxFeatures: Int = 1200, threshold: Int = 20): List<Feature> {
        val candidates = ArrayList<Feature>()
        val margin = PATCH_RADIUS + 1

        for (y in margin until image.height - margin) {
            for (x in margin until image.width - margin) {
                val score = cornerScore(image, x, y, threshold)
                if (score > 0) {
                    candidates.add(Feature(x.toFloat(), y.toFloat(), score, 0f, EMPTY))
                }
            }
        }

        val suppressed = nonMaximumSuppression(candidates, image.width)
        val strongest = suppressed.sortedByDescending { it.score }.take(maxFeatures)

        return strongest.map { candidate ->
            val angle = orientationAt(image, candidate.x.toInt(), candidate.y.toInt())
            Feature(
                x = candidate.x,
                y = candidate.y,
                score = candidate.score,
                angle = angle,
                descriptor = describe(image, candidate.x.toInt(), candidate.y.toInt(), angle),
            )
        }
    }

    /**
     * FAST-9: a corner is a point with nine contiguous ring pixels all brighter or all darker than
     * the centre by [threshold]. Returns 0 for a non-corner, otherwise a strength score.
     */
    private fun cornerScore(image: GrayImage, x: Int, y: Int, threshold: Int): Int {
        val centre = image.at(x, y)
        val bright = centre + threshold
        val dark = centre - threshold

        // Cheap rejection first, using only the four compass points, which discards the
        // overwhelming majority of pixels before the full ring is read.
        //
        // Two of four, not three: the compass points sit four apart on a sixteen-pixel ring, so a
        // window of nine consecutive positions is only guaranteed to contain two of them. Demanding
        // three is the FAST-12 test and rejects the great majority of real FAST-9 corners.
        val north = image.at(x, y - 3)
        val south = image.at(x, y + 3)
        val east = image.at(x + 3, y)
        val west = image.at(x - 3, y)
        val brightCount = countIf(north > bright, south > bright, east > bright, west > bright)
        val darkCount = countIf(north < dark, south < dark, east < dark, west < dark)
        if (brightCount < MIN_COMPASS_AGREEMENT && darkCount < MIN_COMPASS_AGREEMENT) return 0

        val ring = IntArray(16) { image.at(x + CIRCLE_X[it], y + CIRCLE_Y[it]) }
        if (!hasArc(ring, bright, brighter = true) && !hasArc(ring, dark, brighter = false)) return 0

        var strength = 0
        for (value in ring) strength += abs(value - centre)
        return strength
    }

    private fun countIf(vararg conditions: Boolean): Int = conditions.count { it }

    /** True when [ARC_LENGTH] consecutive ring entries all sit past the threshold. */
    private fun hasArc(ring: IntArray, threshold: Int, brighter: Boolean): Boolean {
        var run = 0
        // Wrap around the ring by walking it one and a half times.
        for (i in 0 until ring.size + ARC_LENGTH) {
            val value = ring[i % ring.size]
            val past = if (brighter) value > threshold else value < threshold
            if (past) {
                run++
                if (run >= ARC_LENGTH) return true
            } else {
                run = 0
            }
        }
        return false
    }

    /** Keeps only the strongest corner within a small neighbourhood, thinning dense clusters. */
    private fun nonMaximumSuppression(features: List<Feature>, width: Int): List<Feature> {
        if (features.isEmpty()) return features
        val byPosition = HashMap<Int, Feature>(features.size * 2)
        for (feature in features) {
            val key = feature.y.toInt() * width + feature.x.toInt()
            byPosition[key] = feature
        }
        return features.filter { feature ->
            val fx = feature.x.toInt()
            val fy = feature.y.toInt()
            var isMax = true
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val neighbour = byPosition[(fy + dy) * width + (fx + dx)] ?: continue
                    if (neighbour.score > feature.score) {
                        isMax = false
                    }
                }
            }
            isMax
        }
    }

    /**
     * Patch orientation from the intensity centroid.
     *
     * Steering the descriptor by this angle is what lets matching survive the roll a hand sweep
     * inevitably introduces; an unrotated BRIEF falls apart after a few degrees.
     */
    private fun orientationAt(image: GrayImage, x: Int, y: Int): Float {
        var m01 = 0L
        var m10 = 0L
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) {
            for (dx in -PATCH_RADIUS..PATCH_RADIUS) {
                val value = image.at(x + dx, y + dy).toLong()
                m10 += dx * value
                m01 += dy * value
            }
        }
        return atan2(m01.toDouble(), m10.toDouble()).toFloat()
    }

    /** Oriented BRIEF: compare rotated point pairs, one bit each. */
    private fun describe(image: GrayImage, x: Int, y: Int, angle: Float): LongArray {
        val descriptor = LongArray(DESCRIPTOR_BITS / 64)
        val cosA = cos(angle)
        val sinA = sin(angle)

        for (bit in 0 until DESCRIPTOR_BITS) {
            val base = bit * 4
            val ax = PATTERN[base]
            val ay = PATTERN[base + 1]
            val bx = PATTERN[base + 2]
            val by = PATTERN[base + 3]

            val rax = (cosA * ax - sinA * ay).roundToInt()
            val ray = (sinA * ax + cosA * ay).roundToInt()
            val rbx = (cosA * bx - sinA * by).roundToInt()
            val rby = (sinA * bx + cosA * by).roundToInt()

            if (image.at(x + rax, y + ray) < image.at(x + rbx, y + rby)) {
                descriptor[bit ushr 6] = descriptor[bit ushr 6] or (1L shl (bit and 63))
            }
        }
        return descriptor
    }

    private fun buildPattern(): IntArray {
        val random = Random(seed = 0x8B12E7)
        val pattern = IntArray(DESCRIPTOR_BITS * 4)
        for (i in pattern.indices) {
            pattern[i] = random.nextInt(-PATCH_RADIUS, PATCH_RADIUS + 1)
        }
        return pattern
    }

    private val EMPTY = LongArray(0)
}
