package com.dimroom.editor.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import com.dimroom.domain.model.EditStack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders an edit stack at full resolution without a window, for export.
 *
 * It stands up its own EGL pbuffer context and draws into an FBO, so it can run on any background
 * thread while the preview surface keeps its own context untouched. Because it uses the same
 * [GlProgram] and [EditUniforms] as the preview, exported pixels match what the user was looking at.
 */
class OffscreenRenderer {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /**
     * Applies [edits] to [source] and returns the rendered bitmap.
     *
     * The caller owns [source] and the returned bitmap. Throws [GlException] when the GPU refuses
     * the context or the program — callers report that rather than writing a black file.
     */
    fun render(source: Bitmap, edits: EditStack): Bitmap {
        setUpEgl()
        try {
            val maxTexture = GlProgram.maxTextureSize()
            // Both the input texture and the framebuffer must fit the GPU's limit, so the output
            // size is derived from the (possibly downscaled) texture rather than the original.
            val scaledSource = source.downscaledToFit(maxTexture)
            val (outputWidth, outputHeight) =
                EditUniforms.outputSize(edits.geometry, scaledSource.width, scaledSource.height)
            val targetWidth = outputWidth.coerceAtMost(maxTexture)
            val targetHeight = outputHeight.coerceAtMost(maxTexture)

            val program = GlProgram()
            val textureId = GlProgram.createTexture(scaledSource)
            val framebuffer = IntArray(1)
            val renderTexture = IntArray(1)
            try {
                GLES20.glGenTextures(1, renderTexture, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTexture[0])
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, targetWidth, targetHeight, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
                )
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                GLES20.glGenFramebuffers(1, framebuffer, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, renderTexture[0], 0,
                )
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw GlException("Framebuffer incomplete: 0x${status.toString(16)}")
                }

                GLES20.glViewport(0, 0, targetWidth, targetHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                program.use()
                // The framebuffer is already the output aspect, so no letterboxing is needed.
                program.setMatrix4("uPosMatrix", EditUniforms.identityMatrix())
                program.setMatrix3(
                    "uTexMatrix",
                    EditUniforms.textureMatrix(edits.geometry, scaledSource.width, scaledSource.height),
                )
                EditUniforms.apply(
                    program = program,
                    stack = edits,
                    imageWidth = scaledSource.width,
                    imageHeight = scaledSource.height,
                    splitPx = 0f,
                    showOriginal = false,
                    grainSeed = (scaledSource.width * 31 + scaledSource.height).toFloat() % 100f,
                )
                program.drawQuad(textureId)
                GLES20.glFinish()

                return readPixels(targetWidth, targetHeight)
            } finally {
                if (framebuffer[0] != 0) GLES20.glDeleteFramebuffers(1, framebuffer, 0)
                if (renderTexture[0] != 0) GLES20.glDeleteTextures(1, renderTexture, 0)
                GlProgram.deleteTexture(textureId)
                program.release()
                if (scaledSource !== source) scaledSource.recycle()
            }
        } finally {
            tearDownEgl()
        }
    }

    private fun readPixels(width: Int, height: Int): Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        // GL's origin is bottom-left; flip so the saved file is the right way up.
        return bitmap.flippedVertically().also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) throw GlException("No EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw GlException("eglInitialize failed")
        }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0) ||
            configCount[0] == 0
        ) {
            throw GlException("No suitable EGL config")
        }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context === EGL14.EGL_NO_CONTEXT) throw GlException("eglCreateContext failed")

        // A 1x1 pbuffer is enough: all real drawing happens into an FBO.
        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (surface === EGL14.EGL_NO_SURFACE) throw GlException("eglCreatePbufferSurface failed")

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw GlException("eglMakeCurrent failed")
        }
    }

    private fun tearDownEgl() {
        if (display !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (surface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }
}

private fun Bitmap.downscaledToFit(maxEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return this
    val scale = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
        true,
    )
}

private fun Bitmap.flippedVertically(): Bitmap {
    val matrix = android.graphics.Matrix().apply { setScale(1f, -1f) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, false)
}
