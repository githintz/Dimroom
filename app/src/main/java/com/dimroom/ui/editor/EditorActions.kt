package com.dimroom.ui.editor

import androidx.compose.runtime.Stable
import com.dimroom.domain.model.AspectRatioPreset
import com.dimroom.domain.model.EditStack
import com.dimroom.domain.model.Preset

/**
 * The editing surface the adjustment panels are written against.
 *
 * Panels take this instead of the view model so they stay previewable and testable, and so the
 * gesture protocol ([beginGesture] / [update] / [endGesture]) is explicit at every call site.
 */
@Stable
class EditorActions(
    val beginGesture: () -> Unit,
    val endGesture: () -> Unit,
    val update: ((EditStack) -> EditStack) -> Unit,
    val commit: ((EditStack) -> EditStack) -> Unit,
    val applyAspectRatio: (AspectRatioPreset) -> Unit,
    val setCropScale: (Float) -> Unit,
    val setCropOffset: (Float, Float) -> Unit,
    val rotate: (Int) -> Unit,
    val flipHorizontal: () -> Unit,
    val flipVertical: () -> Unit,
    val resetGeometry: () -> Unit,
    val applyPreset: (Preset) -> Unit,
    val deletePreset: (String) -> Unit,
)

/** Wires an [EditorActions] straight through to [viewModel]. */
fun editorActions(viewModel: EditorViewModel) = EditorActions(
    beginGesture = viewModel::beginGesture,
    endGesture = viewModel::endGesture,
    update = viewModel::updateEdits,
    commit = viewModel::commitEdits,
    applyAspectRatio = viewModel::applyAspectRatio,
    setCropScale = viewModel::setCropScale,
    setCropOffset = viewModel::setCropOffset,
    rotate = viewModel::rotate,
    flipHorizontal = viewModel::flipHorizontal,
    flipVertical = viewModel::flipVertical,
    resetGeometry = viewModel::resetGeometry,
    applyPreset = viewModel::applyPreset,
    deletePreset = viewModel::deletePreset,
)
