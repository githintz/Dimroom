package com.dimroom.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dimroom.data.repository.ImageOrientation
import com.dimroom.data.repository.PhotoRepository
import com.dimroom.data.repository.PresetRepository
import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.AspectRatioPreset
import com.dimroom.domain.model.EditStack
import com.dimroom.domain.model.ExportOptions
import com.dimroom.domain.model.Photo
import com.dimroom.domain.model.Preset
import com.dimroom.editor.export.ExportResult
import com.dimroom.editor.export.ImageExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.min

/** Which adjustment group the bottom panel is showing. */
enum class EditPanel(val displayName: String) {
    LIGHT("Light"),
    COLOR("Color"),
    HSL("HSL"),
    EFFECTS("Effects"),
    GEOMETRY("Crop"),
    PRESETS("Presets"),
}

/** How the preview compares the edit against the original. */
enum class CompareMode {
    OFF,
    SHOW_ORIGINAL,
    SPLIT,
}

data class EditorUiState(
    val photo: Photo? = null,
    val edits: EditStack = EditStack(),
    val isLoading: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val activePanel: EditPanel = EditPanel.LIGHT,
    val compareMode: CompareMode = CompareMode.OFF,
    val splitFraction: Float = 0.5f,
    val presets: List<Preset> = emptyList(),
    val isExporting: Boolean = false,
    val message: String? = null,
    val pendingShareUri: Uri? = null,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val photoRepository: PhotoRepository,
    private val presetRepository: PresetRepository,
    private val exporter: ImageExporter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val photoId: String = checkNotNull(savedStateHandle.get<String>("photoId")) {
        "EditorViewModel requires a photoId argument"
    }

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** Decoded preview-sized source for the GL surface. Null until the photo has loaded. */
    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val undoStack = ArrayDeque<EditStack>()
    private val redoStack = ArrayDeque<EditStack>()

    /** Snapshot taken when a gesture starts, pushed onto the undo stack when it ends. */
    private var gestureSnapshot: EditStack? = null

    private var saveJob: Job? = null
    private var sourceFile: File? = null

    val presets: StateFlow<List<Preset>> = presetRepository.observePresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val photo = photoRepository.getPhoto(photoId)
            val edits = photoRepository.getEditStack(photoId)
            _state.update { it.copy(photo = photo, edits = edits, isLoading = photo != null) }

            if (photo == null) {
                _state.update { it.copy(isLoading = false, message = "That photo is no longer in your library") }
                return@launch
            }

            val file = photoRepository.localFileFor(photo.originalPath)
            sourceFile = file
            if (file == null) {
                _state.update { it.copy(isLoading = false, message = "Could not open the original file") }
                return@launch
            }
            _previewBitmap.value = decodePreview(file)
            _state.update { it.copy(isLoading = false) }
        }

        viewModelScope.launch {
            presets.collect { list -> _state.update { it.copy(presets = list) } }
        }
    }

    // -- Editing ------------------------------------------------------------------------------

    /**
     * Marks the start of a continuous change (a slider drag). The value at this point is what undo
     * returns to, so a drag counts as one history entry rather than hundreds.
     */
    fun beginGesture() {
        if (gestureSnapshot == null) gestureSnapshot = _state.value.edits
    }

    /** Applies a live change without touching history. */
    fun updateEdits(transform: (EditStack) -> EditStack) {
        _state.update { it.copy(edits = transform(it.edits)) }
        scheduleSave()
    }

    /** Ends a continuous change, committing one undo entry if anything actually moved. */
    fun endGesture() {
        val snapshot = gestureSnapshot ?: return
        gestureSnapshot = null
        if (snapshot != _state.value.edits) pushHistory(snapshot)
    }

    /** A discrete change (rotate, preset, reset): its own single undo entry. */
    fun commitEdits(transform: (EditStack) -> EditStack) {
        val before = _state.value.edits
        val after = transform(before)
        if (before == after) return
        pushHistory(before)
        _state.update { it.copy(edits = after) }
        scheduleSave()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.edits)
        _state.update { it.copy(edits = previous) }
        refreshHistoryFlags()
        scheduleSave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.edits)
        _state.update { it.copy(edits = next) }
        refreshHistoryFlags()
        scheduleSave()
    }

    fun resetAll() = commitEdits { EditStack() }

    fun setPanel(panel: EditPanel) = _state.update { it.copy(activePanel = panel) }

    fun setCompareMode(mode: CompareMode) = _state.update { it.copy(compareMode = mode) }

    fun setSplitFraction(fraction: Float) =
        _state.update { it.copy(splitFraction = fraction.coerceIn(0.02f, 0.98f)) }

    // -- Geometry helpers ---------------------------------------------------------------------

    /**
     * Applies an aspect ratio by fitting the largest rect of that shape inside the current image,
     * keeping the crop centred on where it already is.
     */
    fun applyAspectRatio(preset: AspectRatioPreset) = commitEdits { stack ->
        val photo = _state.value.photo
        val geometry = stack.geometry.normalised()
        if (preset == AspectRatioPreset.FREE) {
            return@commitEdits stack.copy(geometry = geometry.copy(aspectRatio = preset))
        }
        if (preset == AspectRatioPreset.ORIGINAL || photo == null) {
            return@commitEdits stack.copy(
                geometry = EditStack.Geometry(
                    rotationDegrees = geometry.rotationDegrees,
                    straightenDegrees = geometry.straightenDegrees,
                    flipHorizontal = geometry.flipHorizontal,
                    flipVertical = geometry.flipVertical,
                    aspectRatio = preset,
                ),
            )
        }

        val target = preset.ratio ?: return@commitEdits stack
        // The requested ratio is in *displayed* terms, so undo the quarter turn before solving.
        val effectiveTarget = if (geometry.rotationDegrees % 180 == 90) 1f / target else target
        val imageAspect = photo.width.toFloat() / photo.height.coerceAtLeast(1)

        // Normalised crop width/height whose pixel ratio equals the target.
        var cropW = 1f
        var cropH = imageAspect / effectiveTarget
        if (cropH > 1f) {
            cropW = 1f / cropH
            cropH = 1f
        }

        val centerX = (geometry.cropLeft + geometry.cropRight) * 0.5f
        val centerY = (geometry.cropTop + geometry.cropBottom) * 0.5f
        val left = (centerX - cropW / 2f).coerceIn(0f, 1f - cropW)
        val top = (centerY - cropH / 2f).coerceIn(0f, 1f - cropH)

        stack.copy(
            geometry = geometry.copy(
                cropLeft = left,
                cropTop = top,
                cropRight = left + cropW,
                cropBottom = top + cropH,
                aspectRatio = preset,
            ).normalised(),
        )
    }

    /** Scales the crop rect about its centre, preserving its current aspect. */
    fun setCropScale(scale: Float) = updateEdits { stack ->
        val geometry = stack.geometry.normalised()
        val clamped = scale.coerceIn(0.1f, 1f)
        val centerX = (geometry.cropLeft + geometry.cropRight) * 0.5f
        val centerY = (geometry.cropTop + geometry.cropBottom) * 0.5f
        // Grow from the current shape, then cap so the rect still fits inside the frame.
        val baseW = geometry.cropWidth
        val baseH = geometry.cropHeight
        val fit = min(1f / baseW, 1f / baseH)
        val width = (baseW * fit * clamped).coerceIn(EditStack.Geometry.MIN_CROP, 1f)
        val height = (baseH * fit * clamped).coerceIn(EditStack.Geometry.MIN_CROP, 1f)
        val left = (centerX - width / 2f).coerceIn(0f, 1f - width)
        val top = (centerY - height / 2f).coerceIn(0f, 1f - height)
        stack.copy(
            geometry = geometry.copy(
                cropLeft = left,
                cropTop = top,
                cropRight = left + width,
                cropBottom = top + height,
            ).normalised(),
        )
    }

    /** Moves the crop rect within the frame; [offsetX] and [offsetY] are 0..1 of the free space. */
    fun setCropOffset(offsetX: Float, offsetY: Float) = updateEdits { stack ->
        val geometry = stack.geometry.normalised()
        val width = geometry.cropWidth
        val height = geometry.cropHeight
        val left = ((1f - width) * offsetX.coerceIn(0f, 1f))
        val top = ((1f - height) * offsetY.coerceIn(0f, 1f))
        stack.copy(
            geometry = geometry.copy(
                cropLeft = left,
                cropTop = top,
                cropRight = left + width,
                cropBottom = top + height,
            ).normalised(),
        )
    }

    fun rotate(quarterTurns: Int) = commitEdits { stack ->
        val geometry = stack.geometry.normalised()
        stack.copy(
            geometry = geometry.copy(
                rotationDegrees = geometry.rotationDegrees + quarterTurns * 90,
            ).normalised(),
        )
    }

    fun flipHorizontal() = commitEdits { stack ->
        stack.copy(geometry = stack.geometry.copy(flipHorizontal = !stack.geometry.flipHorizontal))
    }

    fun flipVertical() = commitEdits { stack ->
        stack.copy(geometry = stack.geometry.copy(flipVertical = !stack.geometry.flipVertical))
    }

    fun resetGeometry() = commitEdits { it.copy(geometry = EditStack.Geometry()) }

    // -- Presets ------------------------------------------------------------------------------

    fun applyPreset(preset: Preset) = commitEdits { stack ->
        stack.applyPreset(preset.edits, preset.id, preset.name)
    }

    fun savePreset(name: String) {
        viewModelScope.launch {
            val preset = presetRepository.savePreset(name, _state.value.edits)
            _state.update { it.copy(message = "Saved preset “${preset.name}”") }
        }
    }

    fun deletePreset(presetId: String) {
        viewModelScope.launch { presetRepository.deletePreset(presetId) }
    }

    // -- Export -------------------------------------------------------------------------------

    fun exportToGallery(options: ExportOptions) = export(options, share = false)

    fun exportForShare(options: ExportOptions) = export(options, share = true)

    private fun export(options: ExportOptions, share: Boolean) {
        val file = sourceFile
        val photo = _state.value.photo
        if (file == null || photo == null) {
            _state.update { it.copy(message = "Nothing to export yet") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isExporting = true) }
            // Flush any debounced edit first so the export renders exactly what is on screen.
            saveJob?.cancel()
            photoRepository.saveEditStack(photoId, _state.value.edits)

            val result = if (share) {
                exporter.exportForShare(file, _state.value.edits, photo.displayName, options)
            } else {
                exporter.exportToGallery(file, _state.value.edits, photo.displayName, options)
            }
            _state.update { current ->
                when (result) {
                    is ExportResult.SavedToGallery ->
                        current.copy(isExporting = false, message = "Saved ${result.displayName} to your gallery")

                    is ExportResult.ReadyToShare ->
                        current.copy(isExporting = false, pendingShareUri = result.uri)

                    is ExportResult.Failed ->
                        current.copy(isExporting = false, message = result.message)
                }
            }
        }
    }

    fun shareIntentFor(uri: Uri) = exporter.shareIntent(uri)

    fun consumeShareUri() = _state.update { it.copy(pendingShareUri = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // -- Internals ----------------------------------------------------------------------------

    private fun pushHistory(previous: EditStack) {
        undoStack.addLast(previous)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        refreshHistoryFlags()
    }

    private fun refreshHistoryFlags() = _state.update {
        it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
    }

    /** Debounces writes so a slider drag results in one save, not one per frame. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            photoRepository.saveEditStack(photoId, _state.value.edits)
        }
    }

    /**
     * Decodes at preview resolution: big enough to judge an edit, small enough that every slider
     * frame stays cheap on the GPU. Export always re-decodes the full original.
     */
    private suspend fun decodePreview(file: File): Bitmap? = withContext(ioDispatcher) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@withContext null

        var sampleSize = 1
        while (longest / (sampleSize * 2) >= PREVIEW_EDGE) sampleSize *= 2

        val raw = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return@withContext null

        val oriented = ImageOrientation.applyExif(raw, file.absolutePath)
        if (oriented !== raw) raw.recycle()
        oriented
    }

    private companion object {
        const val MAX_HISTORY = 50
        const val SAVE_DEBOUNCE_MS = 400L
        const val PREVIEW_EDGE = 2048
    }
}
