package com.dimroom.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dimroom.domain.model.AspectRatioPreset
import com.dimroom.domain.model.ColorBand
import com.dimroom.domain.model.EditStack
import com.dimroom.domain.model.Preset

@Composable
fun LightPanel(edits: EditStack, actions: EditorActions) {
    val light = edits.light
    Column {
        AdjustmentSlider(
            label = "Exposure",
            value = light.exposure,
            valueRange = -5f..5f,
            decimals = 2,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(light = it.light.copy(exposure = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Contrast",
            value = light.contrast,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(light = it.light.copy(contrast = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Highlights",
            value = light.highlights,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(light = it.light.copy(highlights = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Shadows",
            value = light.shadows,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(light = it.light.copy(shadows = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Whites",
            value = light.whites,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(light = it.light.copy(whites = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Blacks",
            value = light.blacks,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(light = it.light.copy(blacks = next)) } },
            onChangeFinished = actions.endGesture,
        )
    }
}

@Composable
fun ColorPanel(edits: EditStack, actions: EditorActions) {
    val color = edits.color
    Column {
        AdjustmentSlider(
            label = "Temperature",
            value = color.temperature,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(color = it.color.copy(temperature = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Tint",
            value = color.tint,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(color = it.color.copy(tint = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Vibrance",
            value = color.vibrance,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(color = it.color.copy(vibrance = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Saturation",
            value = color.saturation,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(color = it.color.copy(saturation = next)) } },
            onChangeFinished = actions.endGesture,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HslPanel(edits: EditStack, actions: EditorActions) {
    var band by remember { mutableStateOf(ColorBand.RED) }
    val adjustment = edits.hsl[band]

    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ColorBand.entries.toList()) { entry ->
                FilterChip(
                    selected = entry == band,
                    onClick = { band = entry },
                    label = { Text(entry.displayName) },
                )
            }
        }
        AdjustmentSlider(
            label = "Hue",
            value = adjustment.hue,
            onGestureStart = actions.beginGesture,
            onChange = { next ->
                actions.update { it.copy(hsl = it.hsl.with(band, it.hsl[band].copy(hue = next))) }
            },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Saturation",
            value = adjustment.saturation,
            onGestureStart = actions.beginGesture,
            onChange = { next ->
                actions.update { it.copy(hsl = it.hsl.with(band, it.hsl[band].copy(saturation = next))) }
            },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Luminance",
            value = adjustment.luminance,
            onGestureStart = actions.beginGesture,
            onChange = { next ->
                actions.update { it.copy(hsl = it.hsl.with(band, it.hsl[band].copy(luminance = next))) }
            },
            onChangeFinished = actions.endGesture,
        )
    }
}

@Composable
fun EffectsPanel(edits: EditStack, actions: EditorActions) {
    val effects = edits.effects
    Column {
        AdjustmentSlider(
            label = "Clarity",
            value = effects.clarity,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(effects = it.effects.copy(clarity = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Dehaze",
            value = effects.dehaze,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(effects = it.effects.copy(dehaze = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Vignette",
            value = effects.vignette,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(effects = it.effects.copy(vignette = next)) } },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Grain",
            value = effects.grain,
            valueRange = 0f..100f,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.update { it.copy(effects = it.effects.copy(grain = next)) } },
            onChangeFinished = actions.endGesture,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeometryPanel(edits: EditStack, actions: EditorActions) {
    val geometry = edits.geometry
    // The crop rect is stored as absolute edges; the sliders drive it as size plus position, which
    // keeps a fixed aspect ratio intact while the user moves things around.
    val cropScale = maxOf(geometry.cropWidth, geometry.cropHeight)
    val freeX = (1f - geometry.cropWidth).takeIf { it > 1e-4f }
    val freeY = (1f - geometry.cropHeight).takeIf { it > 1e-4f }
    val offsetX = freeX?.let { geometry.cropLeft / it } ?: 0.5f
    val offsetY = freeY?.let { geometry.cropTop / it } ?: 0.5f

    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(AspectRatioPreset.entries.toList()) { preset ->
                FilterChip(
                    selected = preset == geometry.aspectRatio,
                    onClick = { actions.applyAspectRatio(preset) },
                    label = { Text(preset.displayName) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { actions.rotate(-1) }) {
                Icon(Icons.Filled.RotateLeft, contentDescription = "Rotate left")
            }
            IconButton(onClick = { actions.rotate(1) }) {
                Icon(Icons.Filled.RotateRight, contentDescription = "Rotate right")
            }
            IconButton(onClick = actions.flipHorizontal) {
                Icon(Icons.Filled.Flip, contentDescription = "Flip horizontally")
            }
            AssistChip(
                onClick = actions.flipVertical,
                label = { Text("Flip ↕") },
            )
            TextButton(onClick = actions.resetGeometry) { Text("Reset crop") }
        }

        AdjustmentSlider(
            label = "Crop size",
            value = cropScale * 100f,
            valueRange = 10f..100f,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.setCropScale(next / 100f) },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Position X",
            value = offsetX * 100f,
            valueRange = 0f..100f,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.setCropOffset(next / 100f, offsetY) },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Position Y",
            value = offsetY * 100f,
            valueRange = 0f..100f,
            onGestureStart = actions.beginGesture,
            onChange = { next -> actions.setCropOffset(offsetX, next / 100f) },
            onChangeFinished = actions.endGesture,
        )
        AdjustmentSlider(
            label = "Straighten",
            value = geometry.straightenDegrees,
            valueRange = -45f..45f,
            decimals = 1,
            onGestureStart = actions.beginGesture,
            onChange = { next ->
                actions.update { it.copy(geometry = it.geometry.copy(straightenDegrees = next)) }
            },
            onChangeFinished = actions.endGesture,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsPanel(
    presets: List<Preset>,
    activePresetId: String?,
    actions: EditorActions,
    onSavePreset: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Presets",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSavePreset) { Text("Save current") }
        }

        if (presets.isEmpty()) {
            Text(
                text = "No presets yet.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(presets, key = { it.id }) { preset ->
                FilterChip(
                    selected = preset.id == activePresetId,
                    onClick = { actions.applyPreset(preset) },
                    label = { Text(preset.name) },
                )
            }
        }

        // Built-in presets are permanent; only the user's own can be removed.
        val userPresets = presets.filter { !it.isBuiltIn }
        if (userPresets.isNotEmpty()) {
            Text(
                text = "Your presets",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(userPresets, key = { "delete-${it.id}" }) { preset ->
                    AssistChip(
                        onClick = { actions.deletePreset(preset.id) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        label = { Text(preset.name) },
                    )
                }
            }
        }
    }
}
