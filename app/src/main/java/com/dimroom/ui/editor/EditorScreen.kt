package com.dimroom.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dimroom.domain.model.ExportOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bitmap by viewModel.previewBitmap.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { editorActions(viewModel) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showExportSheet by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // A finished share export hands back a content URI; launching the chooser is the UI's job.
    LaunchedEffect(state.pendingShareUri) {
        state.pendingShareUri?.let { uri ->
            context.startActivity(viewModel.shareIntentFor(uri))
            viewModel.consumeShareUri()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.photo?.displayName ?: "Edit",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(
                        onClick = viewModel::resetAll,
                        enabled = !state.edits.isIdentity,
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = "Reset to original")
                    }
                    IconButton(onClick = { showExportSheet = true }, enabled = state.photo != null) {
                        Icon(Icons.Filled.SaveAlt, contentDescription = "Export")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D0D)),
            ) {
                PhotoCanvas(
                    bitmap = bitmap,
                    edits = state.edits,
                    compareMode = state.compareMode,
                    splitFraction = state.splitFraction,
                    onSplitDrag = viewModel::setSplitFraction,
                    onError = { /* surfaced through the snackbar via state.message */ },
                    modifier = Modifier.fillMaxSize(),
                )

                if (state.isLoading || state.isExporting) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                if (state.compareMode != CompareMode.OFF) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    ) {
                        Text(
                            text = when (state.compareMode) {
                                CompareMode.SHOW_ORIGINAL -> "Showing original"
                                CompareMode.SPLIT -> "Drag to move the split"
                                CompareMode.OFF -> ""
                            },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            CompareBar(
                compareMode = state.compareMode,
                onSetMode = viewModel::setCompareMode,
                onShare = { viewModel.exportForShare(ExportOptions()) },
            )

            PanelTabs(active = state.activePanel, onSelect = viewModel::setPanel)

            Column(
                modifier = Modifier
                    .height(220.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                when (state.activePanel) {
                    EditPanel.LIGHT -> LightPanel(state.edits, actions)
                    EditPanel.COLOR -> ColorPanel(state.edits, actions)
                    EditPanel.HSL -> HslPanel(state.edits, actions)
                    EditPanel.EFFECTS -> EffectsPanel(state.edits, actions)
                    EditPanel.GEOMETRY -> GeometryPanel(state.edits, actions)
                    EditPanel.PRESETS -> PresetsPanel(
                        presets = state.presets,
                        activePresetId = state.edits.presetId,
                        actions = actions,
                        onSavePreset = { showSavePresetDialog = true },
                    )
                }
            }
        }
    }

    if (showExportSheet) {
        ExportDialog(
            onDismiss = { showExportSheet = false },
            onExport = { options ->
                viewModel.exportToGallery(options)
                showExportSheet = false
            },
            onShare = { options ->
                viewModel.exportForShare(options)
                showExportSheet = false
            },
        )
    }

    if (showSavePresetDialog) {
        SavePresetDialog(
            onDismiss = { showSavePresetDialog = false },
            onSave = { name ->
                viewModel.savePreset(name)
                showSavePresetDialog = false
            },
        )
    }
}

@Composable
private fun CompareBar(
    compareMode: CompareMode,
    onSetMode: (CompareMode) -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = {
                onSetMode(
                    if (compareMode == CompareMode.SHOW_ORIGINAL) {
                        CompareMode.OFF
                    } else {
                        CompareMode.SHOW_ORIGINAL
                    },
                )
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Visibility,
                contentDescription = "Show original",
                tint = if (compareMode == CompareMode.SHOW_ORIGINAL) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(
            onClick = {
                onSetMode(if (compareMode == CompareMode.SPLIT) CompareMode.OFF else CompareMode.SPLIT)
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Compare,
                contentDescription = "Split compare",
                tint = if (compareMode == CompareMode.SPLIT) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Box(modifier = Modifier.weight(1f))
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.IosShare, contentDescription = "Share")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelTabs(active: EditPanel, onSelect: (EditPanel) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(EditPanel.entries.toList()) { panel ->
            FilterChip(
                selected = panel == active,
                onClick = { onSelect(panel) },
                label = { Text(panel.displayName) },
            )
        }
    }
}
