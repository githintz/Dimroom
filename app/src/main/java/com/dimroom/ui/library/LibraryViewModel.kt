package com.dimroom.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dimroom.data.repository.AlbumRepository
import com.dimroom.data.repository.HdrMergeOutcome
import com.dimroom.data.repository.HdrRepository
import com.dimroom.data.repository.PanoramaOutcome
import com.dimroom.data.repository.PanoramaRepository
import com.dimroom.data.repository.PhotoRepository
import com.dimroom.domain.model.Album
import com.dimroom.domain.model.MergeMode
import com.dimroom.domain.model.HdrMergeRequest
import com.dimroom.domain.model.PanoramaRequest
import com.dimroom.domain.model.Photo
import com.dimroom.domain.model.SortOrder
import com.dimroom.editor.hdr.HdrMerger
import com.dimroom.editor.pano.PanoramaStitcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Live state of a running composite, so the grid shows real progress rather than a spinner. */
data class CompositeProgress(val fraction: Float, val stage: String)

data class LibraryUiState(
    val photos: List<Photo> = emptyList(),
    val albums: List<Album> = emptyList(),
    val selectedAlbumId: String? = null,
    val sortOrder: SortOrder = SortOrder.DATE_ADDED_DESC,
    val isImporting: Boolean = false,
    val selection: Set<String> = emptySet(),
    val compositeProgress: CompositeProgress? = null,
    val message: String? = null,
) {
    val isSelecting: Boolean get() = selection.isNotEmpty()
    val selectedAlbum: Album? get() = albums.firstOrNull { it.id == selectedAlbumId }

    val selectedPhotos: List<Photo> get() = photos.filter { it.id in selection }

    /** Merging needs at least two frames, and the engine caps how many it will hold at once. */
    val canMergeSelection: Boolean
        get() = compositeProgress == null &&
            selection.size in HdrMerger.MIN_FRAMES..HdrMerger.MAX_FRAMES

    /** Stitching has its own, wider frame limit than merging. */
    val canStitchSelection: Boolean
        get() = compositeProgress == null &&
            selection.size in PanoramaStitcher.MIN_FRAMES..PanoramaStitcher.MAX_FRAMES

    /** A single selected stack can be pulled apart again. */
    val ungroupableStack: Photo?
        get() = selectedPhotos.singleOrNull()?.takeIf { it.isStack }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val hdrRepository: HdrRepository,
    private val panoramaRepository: PanoramaRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(LibraryFilter())
    private val transient = MutableStateFlow(TransientState())

    private val photos: StateFlow<List<Photo>> = filter
        .flatMapLatest { photoRepository.observePhotos(it.albumId, it.sortOrder) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<LibraryUiState> = combine(
        photos,
        albumRepository.observeAlbums(),
        filter,
        transient,
    ) { photoList, albums, currentFilter, currentTransient ->
        LibraryUiState(
            photos = photoList,
            albums = albums,
            selectedAlbumId = currentFilter.albumId,
            sortOrder = currentFilter.sortOrder,
            isImporting = currentTransient.isImporting,
            // Drop selections for photos that no longer exist.
            selection = currentTransient.selection.intersect(photoList.map { it.id }.toSet()),
            compositeProgress = currentTransient.compositeProgress,
            message = currentTransient.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** File the grid should hand to Coil for [photo]. */
    fun imageFileFor(photo: Photo) = photoRepository.displayFile(photo)

    fun setAlbum(albumId: String?) = filter.update { it.copy(albumId = albumId) }

    fun setSortOrder(sortOrder: SortOrder) = filter.update { it.copy(sortOrder = sortOrder) }

    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            transient.update { it.copy(isImporting = true) }
            val summary = photoRepository.importPhotos(uris, filter.value.albumId)
            transient.update {
                it.copy(
                    isImporting = false,
                    message = when {
                        summary.failed == 0 -> "Imported ${summary.imported} photo${plural(summary.imported)}"
                        summary.imported == 0 -> "Could not import ${summary.failed} photo${plural(summary.failed)}"
                        else -> "Imported ${summary.imported}, skipped ${summary.failed}"
                    },
                )
            }
        }
    }

    fun toggleSelection(photoId: String) = transient.update { current ->
        val next = if (photoId in current.selection) current.selection - photoId else current.selection + photoId
        current.copy(selection = next)
    }

    fun clearSelection() = transient.update { it.copy(selection = emptySet()) }

    fun deleteSelected() {
        val ids = transient.value.selection
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { photoRepository.deletePhoto(it) }
            transient.update {
                it.copy(selection = emptySet(), message = "Deleted ${ids.size} photo${plural(ids.size)}")
            }
        }
    }

    fun addSelectedToAlbum(albumId: String) {
        val ids = transient.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            albumRepository.addPhotos(albumId, ids)
            transient.update {
                it.copy(selection = emptySet(), message = "Added ${ids.size} photo${plural(ids.size)} to album")
            }
        }
    }

    /** Removes the selection from the album currently being browsed; photos stay in the library. */
    fun removeSelectedFromAlbum() {
        val albumId = filter.value.albumId ?: return
        val ids = transient.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { albumRepository.removePhoto(albumId, it) }
            transient.update { it.copy(selection = emptySet(), message = "Removed from album") }
        }
    }

    fun createAlbum(name: String) {
        viewModelScope.launch {
            val album = albumRepository.createAlbum(name)
            transient.update {
                it.copy(message = if (album == null) "That album name is already taken" else "Created “${album.name}”")
            }
        }
    }

    fun renameAlbum(albumId: String, name: String) {
        viewModelScope.launch {
            val ok = albumRepository.renameAlbum(albumId, name)
            if (!ok) transient.update { it.copy(message = "That album name is already taken") }
        }
    }

    fun deleteAlbum(albumId: String) {
        viewModelScope.launch {
            albumRepository.deleteAlbum(albumId)
            if (filter.value.albumId == albumId) filter.update { it.copy(albumId = null) }
            transient.update { it.copy(message = "Album deleted") }
        }
    }

    // -- HDR ------------------------------------------------------------------------------------

    /**
     * Fuses the current selection into a new photo.
     *
     * The merge runs in [viewModelScope] so navigating within the app does not abandon it, and the
     * selection is only cleared once the work has actually finished.
     */
    fun mergeSelectionToHdr(name: String, mode: MergeMode, alignFrames: Boolean) {
        val ids = transient.value.selection.toList()
        if (ids.size < HdrMerger.MIN_FRAMES) return

        viewModelScope.launch {
            transient.update { it.copy(compositeProgress = CompositeProgress(0f, "Preparing")) }
            val outcome = hdrRepository.merge(
                request = HdrMergeRequest(
                    sourcePhotoIds = ids,
                    name = name,
                    mode = mode,
                    alignFrames = alignFrames,
                ),
                listener = { fraction, stage ->
                    transient.update { it.copy(compositeProgress = CompositeProgress(fraction, stage)) }
                },
            )
            transient.update { current ->
                when (outcome) {
                    is HdrMergeOutcome.Success -> current.copy(
                        compositeProgress = null,
                        selection = emptySet(),
                        message = describe(outcome),
                    )

                    is HdrMergeOutcome.Failure -> current.copy(
                        compositeProgress = null,
                        message = outcome.message,
                    )
                }
            }
        }
    }

    /**
     * Stitches the current selection into a panorama.
     *
     * Selection order is the sweep order the stitcher relies on, so it is passed through as the user
     * built it rather than sorted.
     */
    fun stitchSelectionToPanorama(name: String, mode: MergeMode) {
        val ids = transient.value.selection.toList()
        if (ids.size < PanoramaStitcher.MIN_FRAMES) return

        viewModelScope.launch {
            transient.update { it.copy(compositeProgress = CompositeProgress(0f, "Preparing")) }
            val outcome = panoramaRepository.stitch(
                request = PanoramaRequest(sourcePhotoIds = ids, name = name, mode = mode),
                listener = { fraction, stage ->
                    transient.update { it.copy(compositeProgress = CompositeProgress(fraction, stage)) }
                },
            )
            transient.update { current ->
                when (outcome) {
                    is PanoramaOutcome.Success -> current.copy(
                        compositeProgress = null,
                        selection = emptySet(),
                        message = describe(outcome),
                    )

                    is PanoramaOutcome.Failure -> current.copy(
                        compositeProgress = null,
                        message = outcome.message,
                    )
                }
            }
        }
    }

    private fun describe(outcome: PanoramaOutcome.Success): String {
        val grouping = when (outcome.mode) {
            MergeMode.GROUPED -> ", grouped as one"
            MergeMode.SEPARATE -> ", originals kept separately"
        }
        return "Stitched ${outcome.sourceCount} photos into “${outcome.displayName}”" + grouping
    }

    fun ungroupSelectedStack() {
        val stackId = state.value.ungroupableStack?.stackId ?: return
        viewModelScope.launch {
            hdrRepository.ungroup(stackId)
            transient.update {
                it.copy(selection = emptySet(), message = "Ungrouped — the originals are back in your library")
            }
        }
    }

    private fun describe(outcome: HdrMergeOutcome.Success): String {
        val base = "Merged ${outcome.sourceCount} photos into “${outcome.displayName}”"
        val grouping = when (outcome.mode) {
            MergeMode.GROUPED -> ", grouped as one"
            MergeMode.SEPARATE -> ", originals kept separately"
        }
        val alignment = if (outcome.alignedFrames > 0) {
            " · realigned ${outcome.alignedFrames} frame${plural(outcome.alignedFrames)}"
        } else {
            ""
        }
        return base + grouping + alignment
    }

    fun consumeMessage() = transient.update { it.copy(message = null) }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    private data class LibraryFilter(
        val albumId: String? = null,
        val sortOrder: SortOrder = SortOrder.DATE_ADDED_DESC,
    )

    private data class TransientState(
        val isImporting: Boolean = false,
        val selection: Set<String> = emptySet(),
        val compositeProgress: CompositeProgress? = null,
        val message: String? = null,
    )
}
