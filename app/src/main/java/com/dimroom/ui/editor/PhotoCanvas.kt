package com.dimroom.ui.editor

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dimroom.domain.model.EditStack
import com.dimroom.editor.gl.PhotoRenderer
import kotlin.math.abs

/**
 * The live GPU preview.
 *
 * Zoom and pan are handled by the shader's position matrix rather than by transforming the view:
 * a [GLSurfaceView] is backed by a real surface that Compose's `graphicsLayer` cannot transform, and
 * doing it in GL keeps the image resampled at full preview resolution while zoomed in.
 */
@Composable
fun PhotoCanvas(
    bitmap: Bitmap?,
    edits: EditStack,
    compareMode: CompareMode,
    splitFraction: Float,
    modifier: Modifier = Modifier,
    onSplitDrag: ((Float) -> Unit)? = null,
    onError: (String) -> Unit = {},
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var surfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }

    val renderer = remember { PhotoRenderer(onError = onError) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Push state into the renderer and ask for exactly one frame per change: RENDERMODE_WHEN_DIRTY
    // means an idle editor costs nothing.
    LaunchedEffect(bitmap) {
        bitmap?.let {
            renderer.setBitmap(it)
            surfaceView?.requestRender()
        }
    }
    LaunchedEffect(edits, compareMode, splitFraction, zoom, panX, panY) {
        renderer.setEdits(edits)
        renderer.showOriginal = compareMode == CompareMode.SHOW_ORIGINAL
        renderer.splitFraction = if (compareMode == CompareMode.SPLIT) splitFraction else 0f
        renderer.zoom = zoom
        renderer.panX = panX
        renderer.panY = panY
        surfaceView?.requestRender()
    }

    DisposableEffect(lifecycleOwner, surfaceView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surfaceView?.onResume()
                Lifecycle.Event.ON_PAUSE -> surfaceView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.pointerInput(compareMode) {
            if (compareMode == CompareMode.SPLIT && onSplitDrag != null) {
                // In split mode the same drag moves the divider instead of panning.
                detectHorizontalDrag { x -> onSplitDrag(x / size.width.toFloat()) }
            } else {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val nextZoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    // Clip-space pan: the visible half-extent shrinks as zoom grows, so the
                    // allowed travel grows with it and the photo can never be dragged off-screen.
                    val limit = (nextZoom - 1f).coerceAtLeast(0f)
                    zoom = nextZoom
                    panX = (panX + pan.x * 2f / size.width).coerceIn(-limit, limit)
                    panY = (panY - pan.y * 2f / size.height).coerceIn(-limit, limit)
                    if (abs(nextZoom - 1f) < 0.02f) {
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                    }
                }
            }
        },
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                surfaceView = this
            }
        },
        onRelease = { view ->
            view.queueEvent { renderer.release() }
            view.onPause()
            surfaceView = null
        },
    )
}

/** Minimal horizontal-drag detector used for the split-compare divider. */
private suspend fun PointerInputScope.detectHorizontalDrag(onPosition: (Float) -> Unit) {
    detectDragGestures(
        onDragStart = { offset -> onPosition(offset.x) },
    ) { change, _ ->
        onPosition(change.position.x)
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f
