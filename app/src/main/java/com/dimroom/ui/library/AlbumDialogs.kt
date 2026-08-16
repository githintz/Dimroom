package com.dimroom.ui.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dimroom.domain.model.Album

/** Which album dialog, if any, the library is currently showing. */
sealed interface AlbumDialog {
    data object Create : AlbumDialog
    data class Rename(val album: Album) : AlbumDialog
    data class Delete(val album: Album) : AlbumDialog
}

@Composable
fun AlbumDialogs(
    dialog: AlbumDialog,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    when (dialog) {
        AlbumDialog.Create -> NameDialog(
            title = "New album",
            initialName = "",
            confirmLabel = "Create",
            onDismiss = onDismiss,
            onConfirm = { name ->
                onCreate(name)
                onDismiss()
            },
        )

        is AlbumDialog.Rename -> NameDialog(
            title = "Rename album",
            initialName = dialog.album.name,
            confirmLabel = "Rename",
            onDismiss = onDismiss,
            onConfirm = { name ->
                onRename(dialog.album.id, name)
                onDismiss()
            },
        )

        is AlbumDialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete “${dialog.album.name}”?") },
            text = {
                Text(
                    "The album and its grouping are removed. The photos themselves stay in your " +
                        "library along with their edits.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(dialog.album.id)
                    onDismiss()
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Album name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
