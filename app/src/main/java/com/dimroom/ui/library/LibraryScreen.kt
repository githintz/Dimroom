package com.dimroom.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dimroom.domain.model.Album
import com.dimroom.domain.model.Photo
import com.dimroom.domain.model.PhotoKind
import com.dimroom.domain.model.SortOrder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryScreen(
    onOpenPhoto: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSortMenu by remember { mutableStateOf(false) }
    var showAlbumMenu by remember { mutableStateOf(false) }
    var showAddToAlbumMenu by remember { mutableStateOf(false) }
    var albumDialog by remember { mutableStateOf<AlbumDialog?>(null) }
    var showHdrDialog by remember { mutableStateOf(false) }

    // The system photo picker needs no storage permission and returns per-item read grants.
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT_AT_ONCE),
    ) { uris -> viewModel.importPhotos(uris) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.isSelecting) {
                TopAppBar(
                    title = { Text("${state.selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        // Merging needs a bracket, so the action only lights up for a workable
                        // selection rather than failing after the user commits to it.
                        IconButton(
                            onClick = { showHdrDialog = true },
                            enabled = state.canMergeSelection,
                        ) {
                            Icon(Icons.Filled.HdrOn, contentDescription = "Merge to HDR")
                        }
                        state.ungroupableStack?.let {
                            IconButton(onClick = viewModel::ungroupSelectedStack) {
                                Icon(Icons.Filled.LayersClear, contentDescription = "Ungroup HDR")
                            }
                        }
                        Box {
                            IconButton(onClick = { showAddToAlbumMenu = true }) {
                                Icon(Icons.Filled.LibraryAdd, contentDescription = "Add to album")
                            }
                            DropdownMenu(
                                expanded = showAddToAlbumMenu,
                                onDismissRequest = { showAddToAlbumMenu = false },
                            ) {
                                if (state.albums.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No albums yet") },
                                        onClick = { showAddToAlbumMenu = false },
                                        enabled = false,
                                    )
                                }
                                state.albums.forEach { album ->
                                    DropdownMenuItem(
                                        text = { Text(album.name) },
                                        onClick = {
                                            viewModel.addSelectedToAlbum(album.id)
                                            showAddToAlbumMenu = false
                                        },
                                    )
                                }
                                if (state.selectedAlbumId != null) {
                                    DropdownMenuItem(
                                        text = { Text("Remove from this album") },
                                        onClick = {
                                            viewModel.removeSelectedFromAlbum()
                                            showAddToAlbumMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = viewModel::deleteSelected) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(state.selectedAlbum?.name ?: "Library") },
                    actions = {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.displayName) },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            showSortMenu = false
                                        },
                                        trailingIcon = {
                                            if (order == state.sortOrder) {
                                                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showAlbumMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Albums")
                            }
                            DropdownMenu(
                                expanded = showAlbumMenu,
                                onDismissRequest = { showAlbumMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("New album") },
                                    onClick = {
                                        albumDialog = AlbumDialog.Create
                                        showAlbumMenu = false
                                    },
                                )
                                state.selectedAlbum?.let { album ->
                                    DropdownMenuItem(
                                        text = { Text("Rename “${album.name}”") },
                                        onClick = {
                                            albumDialog = AlbumDialog.Rename(album)
                                            showAlbumMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete “${album.name}”") },
                                        onClick = {
                                            albumDialog = AlbumDialog.Delete(album)
                                            showAlbumMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.isSelecting) {
                ExtendedFloatingActionButton(
                    onClick = {
                        pickPhotos.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    icon = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
                    text = { Text("Import") },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AlbumFilterRow(
                albums = state.albums,
                selectedAlbumId = state.selectedAlbumId,
                onSelect = viewModel::setAlbum,
                onCreateAlbum = { albumDialog = AlbumDialog.Create },
            )

            if (state.isImporting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Importing photos…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            // Fusion takes real time on a big bracket, so report the actual stage and fraction
            // rather than an indefinite spinner.
            state.hdrProgress?.let { progress ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "${progress.stage}…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }

            when {
                state.photos.isEmpty() && !state.isImporting -> EmptyLibrary(
                    inAlbum = state.selectedAlbumId != null,
                )

                else -> PhotoGrid(
                    photos = state.photos,
                    selection = state.selection,
                    isSelecting = state.isSelecting,
                    imageFileFor = viewModel::imageFileFor,
                    onOpen = onOpenPhoto,
                    onToggleSelect = viewModel::toggleSelection,
                )
            }
        }
    }

    if (showHdrDialog) {
        HdrMergeDialog(
            photoCount = state.selection.size,
            onDismiss = { showHdrDialog = false },
            onConfirm = { name, mode, alignFrames ->
                viewModel.mergeSelectionToHdr(name, mode, alignFrames)
                showHdrDialog = false
            },
        )
    }

    albumDialog?.let { dialog ->
        AlbumDialogs(
            dialog = dialog,
            onDismiss = { albumDialog = null },
            onCreate = viewModel::createAlbum,
            onRename = viewModel::renameAlbum,
            onDelete = viewModel::deleteAlbum,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumFilterRow(
    albums: List<Album>,
    selectedAlbumId: String?,
    onSelect: (String?) -> Unit,
    onCreateAlbum: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedAlbumId == null,
                onClick = { onSelect(null) },
                label = { Text("All photos") },
            )
        }
        items(albums, key = { it.id }) { album ->
            FilterChip(
                selected = album.id == selectedAlbumId,
                onClick = { onSelect(album.id) },
                label = { Text("${album.name} · ${album.photoCount}") },
            )
        }
        item {
            FilterChip(
                selected = false,
                onClick = onCreateAlbum,
                label = { Text("New album") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(
    photos: List<Photo>,
    selection: Set<String>,
    isSelecting: Boolean,
    imageFileFor: (Photo) -> java.io.File?,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(photos, key = { it.id }) { photo ->
            val selected = photo.id in selection
            val file = remember(photo.id, photo.thumbnailPath) { imageFileFor(photo) }

            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        onClick = { if (isSelecting) onToggleSelect(photo.id) else onOpen(photo.id) },
                        onLongClick = { onToggleSelect(photo.id) },
                    ),
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = photo.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                if (photo.hasEdits) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Edited",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .size(14.dp),
                    )
                }

                // A merged photo is labelled; a grouped one also says how many photos it stands for.
                if (photo.kind == PhotoKind.HDR_MERGE || photo.isStack) {
                    TileBadge(
                        text = if (photo.isStack) "HDR ${photo.stackSize}" else "HDR",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    )
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    )
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp),
                    )
                }
            }
        }
    }
}

/** Small dark pill used for tile annotations, legible over any photo. */
@Composable
private fun TileBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyLibrary(inAlbum: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AddPhotoAlternate,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (inAlbum) "This album is empty" else "No photos yet",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = if (inAlbum) {
                    "Select photos in your library and add them to this album."
                } else {
                    "Tap Import to bring photos in from your device. Originals are copied into " +
                        "Dimroom and never modified."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private const val MAX_IMPORT_AT_ONCE = 50
