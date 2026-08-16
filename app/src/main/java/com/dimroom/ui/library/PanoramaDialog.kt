package com.dimroom.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dimroom.domain.model.MergeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Confirms a panorama stitch.
 *
 * Says the one thing the user has to get right — selection order is sweep order — rather than
 * leaving it to be discovered from a failure message.
 */
@Composable
fun PanoramaDialog(
    photoCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, mode: MergeMode) -> Unit,
) {
    var name by remember { mutableStateOf(defaultPanoramaName()) }
    var mode by remember { mutableStateOf(MergeMode.GROUPED) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stitch $photoCount photos") },
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
                    text = "Photos are stitched in the order you selected them, and each one needs " +
                        "to overlap the one before it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )

                Text(
                    text = "In your library",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                MergeMode.entries.forEach { option ->
                    PanoramaModeOption(
                        mode = option,
                        selected = option == mode,
                        photoCount = photoCount,
                        onSelect = { mode = option },
                    )
                }

                Text(
                    text = "Your originals are never modified. The panorama is added as a new, " +
                        "fully editable photo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, mode) }, enabled = name.isNotBlank()) {
                Text("Stitch")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PanoramaModeOption(
    mode: MergeMode,
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
            Text(
                text = mode.displayName.replace("HDR", "panorama"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = mode.description
                    .replace("merged HDR", "panorama")
                    .replace("the originals", "the $photoCount originals"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun defaultPanoramaName(): String =
    "Panorama " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
