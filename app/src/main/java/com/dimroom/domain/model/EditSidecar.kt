package com.dimroom.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk envelope for an edit stack: `/Edits/{photoId}.json`.
 *
 * The envelope carries just enough identity for a folder of sidecars to be reconstructed without a
 * database — which is exactly what a cloud provider will need when it syncs a folder it did not
 * write. Keep this file format stable; it is the contract shared by every [com.dimroom.data.storage.StorageProvider].
 */
@Serializable
data class EditSidecar(
    @SerialName("schemaVersion") val schemaVersion: Int = EditStack.SCHEMA_VERSION,
    @SerialName("photoId") val photoId: String,
    @SerialName("originalFileName") val originalFileName: String? = null,
    @SerialName("updatedAt") val updatedAt: Long = 0L,
    @SerialName("edits") val edits: EditStack = EditStack(),
)

/** Serialisation entry point for sidecars and preset payloads. */
object EditSerialization {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodeSidecar(sidecar: EditSidecar): String = json.encodeToString(EditSidecar.serializer(), sidecar)

    /** Returns null rather than throwing when a sidecar is corrupt, so one bad file can't brick the library. */
    fun decodeSidecar(raw: String): EditSidecar? = runCatching {
        json.decodeFromString(EditSidecar.serializer(), raw)
    }.getOrNull()

    fun encodeStack(stack: EditStack): String = json.encodeToString(EditStack.serializer(), stack)

    fun decodeStack(raw: String): EditStack = runCatching {
        json.decodeFromString(EditStack.serializer(), raw)
    }.getOrDefault(EditStack())
}
