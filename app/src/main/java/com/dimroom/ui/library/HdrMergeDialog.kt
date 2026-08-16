package com.dimroom.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dimroom.domain.model.HdrMergeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Confirms an HDR merge.
 *
 * The two outcomes differ in what happens to the brackets afterwards, not in the merge itself, so
 * the wording describes the library result rather than the algorithm.
 */
@Composable
fun HdrMergeDialog(
    photoCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, mode: HdrMergeMode, alignFrames: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName()) }
    var mode by remember { mutableStateOf(HdrMergeMode.GROUPED) }
    var alignFrames by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge $photoCount photos to HDR") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "In your library",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                HdrMergeMode.entries.forEach { option ->
                    ModeOption(
                        mode = option,
                        selected = option == mode,
                        photoCount = photoCount,
                        onSelect = { mode = option },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .toggleable(
                            value = alignFrames,
                            onValueChange = { alignFrames = it },
                            role = Role.Checkbox,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = alignFrames, onCheckedChange = null)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Align frames", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Corrects small handheld shifts between shots. Turn off for " +
                                "tripod brackets to merge a little faster.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = "Your originals are never modified. The merge is added as a new, fully " +
                        "editable photo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, mode, alignFrames) },
                enabled = name.isNotBlank(),
            ) {
                Text("Merge")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ModeOption(
    mode: HdrMergeMode,
    selected: Boolean,
    photoCount: Int,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(mode.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = mode.description.replace("the originals", "the $photoCount originals"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun defaultName(): String =
    "HDR " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
