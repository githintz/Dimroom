package com.dimroom.editor.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.dimroom.domain.model.EditStack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the live preview.
 *
 * The renderer owns no state the UI thread can mutate directly: the view model publishes an
 * immutable [EditStack] into [pendingEdits] and the GL thread picks it up on the next frame. That
 * keeps slider drags lock-free while still being safe across the two threads.
 */
class PhotoRenderer(
    private val onError: (String) -> Unit,
) : GLSurfaceView.Renderer {

    /**
     * The bitmap stays referenced (rather than being consumed) because EGL can drop the context
     * whenever the app is backgrounded, and the texture then has to be uploaded again.
     */
    private val sourceBitmap = AtomicReference<Bitmap?>(null)
    private val needsUpload = AtomicBoolean(false)
    private val pendingEdits = AtomicReference(EditStack())

    @Volatile
    var showOriginal: Boolean = false

    /** Split-compare divider as a fraction of view width; <= 0 disables it. */
    @Volatile
    var splitFraction: Float = 0f

    @Volatile
    var zoom: Float = 1f

    @Volatile
    var panX: Float = 0f

    @Volatile
    var panY: Float = 0f

    private var program: GlProgram? = null
    private var textureId = 0
    private var imageWidth = 1
    private var imageHeight = 1
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var grainSeed = 0f

    /** Hands a decoded bitmap to the GL thread; the previous texture is replaced on the next frame. */
    fun setBitmap(bitmap: Bitmap) {
        sourceBitmap.set(bitmap)
        needsUpload.set(true)
    }

    fun setEdits(edits: EditStack) {
        pendingEdits.set(edits)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.07f, 0.07f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        // The context can be recreated after a backgrounding; rebuild everything that lived in it.
        textureId = 0
        needsUpload.set(sourceBitmap.get() != null)
        program = try {
            GlProgram()
        } catch (e: GlException) {
            Log.e(TAG, "Shader build failed", e)
            onError(e.message ?: "Could not initialise the photo renderer")
            null
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val activeProgram = program ?: return

        if (needsUpload.compareAndSet(true, false)) {
            sourceBitmap.get()?.takeIf { !it.isRecycled }?.let { bitmap ->
                GlProgram.deleteTexture(textureId)
                textureId = GlProgram.createTexture(bitmap)
                imageWidth = bitmap.width.coerceAtLeast(1)
                imageHeight = bitmap.height.coerceAtLeast(1)
                grainSeed = (bitmap.width * 31 + bitmap.height).toFloat() % 100f
            }
        }
        if (textureId == 0) return

        val edits = pendingEdits.get()
        val (contentWidth, contentHeight) = EditUniforms.outputSize(edits.geometry, imageWidth, imageHeight)

        activeProgram.use()
        activeProgram.setMatrix4(
            "uPosMatrix",
            EditUniforms.positionMatrix(
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                contentWidth = contentWidth,
                contentHeight = contentHeight,
                zoom = zoom,
                panX = panX,
                panY = panY,
            ),
        )
        activeProgram.setMatrix3("uTexMatrix", EditUniforms.textureMatrix(edits.geometry, imageWidth, imageHeight))
        EditUniforms.apply(
            program = activeProgram,
            stack = edits,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            splitPx = if (splitFraction > 0f) splitFraction * viewportWidth else 0f,
            showOriginal = showOriginal,
            grainSeed = grainSeed,
        )
        activeProgram.drawQuad(textureId)
    }

    /** Called on the GL thread when the surface goes away. */
    fun release() {
        GlProgram.deleteTexture(textureId)
        textureId = 0
        program?.release()
        program = null
    }

    private companion object {
        const val TAG = "PhotoRenderer"
    }
}
