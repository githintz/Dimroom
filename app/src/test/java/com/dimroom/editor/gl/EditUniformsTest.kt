package com.dimroom.editor.gl

import com.dimroom.domain.model.EditStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry maths runs on the JVM, so it can be pinned down without a device. These are the cases
 * that visibly break the preview when they are wrong: rotated aspect, crop mapping and flips.
 */
class EditUniformsTest {

    @Test
    fun `output size follows the crop rect`() {
        val geometry = EditStack.Geometry(cropLeft = 0.25f, cropRight = 0.75f)

        val (width, height) = EditUniforms.outputSize(geometry, 4000, 3000)

        assertEquals(2000, width)
        assertEquals(3000, height)
    }

    @Test
    fun `a quarter turn swaps the output axes`() {
        val geometry = EditStack.Geometry(rotationDegrees = 90)

        val (width, height) = EditUniforms.outputSize(geometry, 4000, 3000)

        assertEquals(3000, width)
        assertEquals(4000, height)
    }

    @Test
    fun `identity geometry maps the quad onto the whole image`() {
        val matrix = EditUniforms.textureMatrix(EditStack.Geometry(), 1000, 500)

        assertEquals(0f, mapX(matrix, 0f, 0f), TOLERANCE)
        assertEquals(0f, mapY(matrix, 0f, 0f), TOLERANCE)
        assertEquals(1f, mapX(matrix, 1f, 1f), TOLERANCE)
        assertEquals(1f, mapY(matrix, 1f, 1f), TOLERANCE)
    }

    @Test
    fun `crop maps the quad onto just the cropped region`() {
        val geometry = EditStack.Geometry(
            cropLeft = 0.2f,
            cropTop = 0.1f,
            cropRight = 0.6f,
            cropBottom = 0.7f,
        )

        val matrix = EditUniforms.textureMatrix(geometry, 1000, 1000)

        assertEquals(0.2f, mapX(matrix, 0f, 0f), TOLERANCE)
        assertEquals(0.1f, mapY(matrix, 0f, 0f), TOLERANCE)
        assertEquals(0.6f, mapX(matrix, 1f, 1f), TOLERANCE)
        assertEquals(0.7f, mapY(matrix, 1f, 1f), TOLERANCE)
    }

    @Test
    fun `horizontal flip mirrors the sampled column`() {
        val matrix = EditUniforms.textureMatrix(
            EditStack.Geometry(flipHorizontal = true),
            1000,
            1000,
        )

        assertEquals(1f, mapX(matrix, 0f, 0f), TOLERANCE)
        assertEquals(0f, mapX(matrix, 1f, 0f), TOLERANCE)
        // The vertical axis is untouched.
        assertEquals(0f, mapY(matrix, 0f, 0f), TOLERANCE)
    }

    @Test
    fun `a quarter turn maps the output's top-left to the source's bottom-left`() {
        val matrix = EditUniforms.textureMatrix(EditStack.Geometry(rotationDegrees = 90), 1000, 1000)

        assertEquals(0f, mapX(matrix, 0f, 0f), TOLERANCE)
        assertEquals(1f, mapY(matrix, 0f, 0f), TOLERANCE)
        assertEquals(1f, mapX(matrix, 1f, 1f), TOLERANCE)
        assertEquals(0f, mapY(matrix, 1f, 1f), TOLERANCE)
    }

    @Test
    fun `straightening shrinks the sampled rect so no corner falls outside`() {
        val matrix = EditUniforms.textureMatrix(
            EditStack.Geometry(straightenDegrees = 10f),
            1000,
            1000,
        )

        listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (u, v) ->
            val x = mapX(matrix, u, v)
            val y = mapY(matrix, u, v)
            assertTrue("x=$x out of bounds for ($u,$v)", x >= -BOUNDS_SLACK && x <= 1f + BOUNDS_SLACK)
            assertTrue("y=$y out of bounds for ($u,$v)", y >= -BOUNDS_SLACK && y <= 1f + BOUNDS_SLACK)
        }
    }

    @Test
    fun `letterboxing keeps a wide photo inside a square viewport`() {
        val matrix = EditUniforms.positionMatrix(1000, 1000, 2000, 1000)

        // Column-major mat4: scaleX at index 0, scaleY at index 5.
        assertEquals(1f, matrix[0], TOLERANCE)
        assertEquals(0.5f, matrix[5], TOLERANCE)
    }

    @Test
    fun `letterboxing keeps a tall photo inside a wide viewport`() {
        val matrix = EditUniforms.positionMatrix(2000, 1000, 1000, 1000)

        assertEquals(0.5f, matrix[0], TOLERANCE)
        assertEquals(1f, matrix[5], TOLERANCE)
    }

    // Column-major mat3 multiply, matching how GLSL evaluates `uTexMatrix * vec3(uv, 1.0)`.
    private fun mapX(m: FloatArray, u: Float, v: Float) = m[0] * u + m[3] * v + m[6]

    private fun mapY(m: FloatArray, u: Float, v: Float) = m[1] * u + m[4] * v + m[7]

    private companion object {
        const val TOLERANCE = 1e-4f

        /** The inscribed-rect fit lands exactly on the edge, so allow a hair of float slop. */
        const val BOUNDS_SLACK = 1e-3f
    }
}
