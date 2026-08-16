package com.dimroom.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dimroom.data.repository.AlbumRepository
import com.dimroom.data.repository.PhotoRepository
import com.dimroom.domain.model.Album
import com.dimroom.domain.model.Photo
import com.dimroom.domain.model.SortOrder
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

data class LibraryUiState(
    val photos: List<Photo> = emptyList(),
    val albums: List<Album> = emptyList(),
    val selectedAlbumId: String? = null,
    val sortOrder: SortOrder = SortOrder.DATE_ADDED_DESC,
    val isImporting: Boolean = false,
    val selection: Set<String> = emptySet(),
    val message: String? = null,
) {
    val isSelecting: Boolean get() = selection.isNotEmpty()
    val selectedAlbum: Album? get() = albums.firstOrNull { it.id == selectedAlbumId }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
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

    fun consumeMessage() = transient.update { it.copy(message = null) }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    private data class LibraryFilter(
        val albumId: String? = null,
        val sortOrder: SortOrder = SortOrder.DATE_ADDED_DESC,
    )

    private data class TransientState(
        val isImporting: Boolean = false,
        val selection: Set<String> = emptySet(),
        val message: String? = null,
    )
}
