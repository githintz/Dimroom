package com.dimroom.editor.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Thrown when a shader or program fails to build; callers surface it rather than drawing garbage. */
class GlException(message: String) : RuntimeException(message)

/**
 * Compiled [EditShaders] program plus the full-screen quad it draws.
 *
 * One instance belongs to exactly one EGL context. Both the preview surface and the offscreen
 * exporter create their own.
 */
class GlProgram {

    private var programHandle = 0

    private val attribPosition: Int
    private val attribTexCoord: Int
    private val uniforms = mutableMapOf<String, Int>()

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, EditShaders.VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, EditShaders.FRAGMENT_SHADER)
        programHandle = GLES20.glCreateProgram()
        if (programHandle == 0) throw GlException("glCreateProgram failed")
        GLES20.glAttachShader(programHandle, vertexShader)
        GLES20.glAttachShader(programHandle, fragmentShader)
        GLES20.glLinkProgram(programHandle)

        val status = IntArray(1)
        GLES20.glGetProgramiv(programHandle, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(programHandle)
            GLES20.glDeleteProgram(programHandle)
            programHandle = 0
            throw GlException("Program link failed: $log")
        }
        // The program keeps the compiled objects alive; drop our references immediately.
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        attribPosition = GLES20.glGetAttribLocation(programHandle, "aPosition")
        attribTexCoord = GLES20.glGetAttribLocation(programHandle, "aTexCoord")

        vertexBuffer = floatBufferOf(QUAD_VERTICES)
        texCoordBuffer = floatBufferOf(QUAD_TEX_COORDS)
    }

    fun use() = GLES20.glUseProgram(programHandle)

    fun uniform(name: String): Int = uniforms.getOrPut(name) {
        GLES20.glGetUniformLocation(programHandle, name)
    }

    fun setFloat(name: String, value: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform1f(location, value)
    }

    fun setVec2(name: String, x: Float, y: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform2f(location, x, y)
    }

    fun setFloatArray(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform1fv(location, values.size, values, 0)
    }

    fun setVec3Array(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform3fv(location, values.size / 3, values, 0)
    }

    fun setMatrix4(name: String, matrix: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniformMatrix4fv(location, 1, false, matrix, 0)
    }

    fun setMatrix3(name: String, matrix: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniformMatrix3fv(location, 1, false, matrix, 0)
    }

    /** Binds [textureId] to unit 0 and draws the quad. */
    fun drawQuad(textureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val samplerLocation = uniform("uTexture")
        if (samplerLocation >= 0) GLES20.glUniform1i(samplerLocation, 0)

        GLES20.glEnableVertexAttribArray(attribPosition)
        GLES20.glVertexAttribPointer(attribPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(attribTexCoord)
        GLES20.glVertexAttribPointer(attribTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(attribPosition)
        GLES20.glDisableVertexAttribArray(attribTexCoord)
    }

    fun release() {
        if (programHandle != 0) {
            GLES20.glDeleteProgram(programHandle)
            programHandle = 0
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw GlException("glCreateShader failed for type $type")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw GlException("Shader compile failed: $log")
        }
        return shader
    }

    companion object {
        /** Triangle-strip unit quad in clip space. */
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        /** Matching texture coordinates, flipped vertically because bitmaps are top-down. */
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )

        fun floatBufferOf(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        /** Uploads [bitmap] into a freshly created clamped, linearly filtered texture. */
        fun createTexture(bitmap: Bitmap): Int {
            val handles = IntArray(1)
            GLES20.glGenTextures(1, handles, 0)
            val textureId = handles[0]
            if (textureId == 0) throw GlException("glGenTextures failed")
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            return textureId
        }

        fun deleteTexture(textureId: Int) {
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }

        /** Largest texture the current context can hold, used to decide whether to downscale. */
        fun maxTextureSize(): Int {
            val value = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, value, 0)
            return value[0].takeIf { it > 0 } ?: 2048
        }
    }
}
