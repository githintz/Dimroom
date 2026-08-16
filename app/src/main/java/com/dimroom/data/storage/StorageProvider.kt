package com.dimroom.data.storage

import java.io.File

/**
 * The single seam between Dimroom and wherever photos physically live.
 *
 * Everything above this interface — repositories, view models, UI — is written against paths and
 * ids only, never against `File`, `Uri` or any cloud SDK type. Adding a backend therefore means
 * writing one implementation and changing one Hilt binding; see `README.md`.
 *
 * ### Layout contract
 * Implementations must use the same logical layout so a folder written by one provider can be read
 * by another:
 * ```
 * /Originals/{albumName}/{photoId}.jpg   untouched imported bytes
 * /Edits/{photoId}.json                  non-destructive edit sidecar
 * /Previews/{photoId}_thumb.jpg          regenerable grid thumbnail
 * ```
 * Paths passed across this interface are always provider-relative, use `/` separators, and never
 * start with a separator.
 *
 * All methods are suspending and must be safe to call from any dispatcher; implementations do their
 * own IO confinement.
 */
interface StorageProvider {

    /** Lists photos directly inside [folderPath] (for example `Originals/Portraits`). */
    suspend fun listPhotos(folderPath: String): List<PhotoMetadata>

    /** Copies [localFile] to [targetPath], creating parent folders as needed. */
    suspend fun uploadPhoto(localFile: File, targetPath: String): StorageResult

    /** Fetches the object identified by [remoteId] into [targetLocalFile]. */
    suspend fun downloadPhoto(remoteId: String, targetLocalFile: File): StorageResult

    /** Writes the edit sidecar for [photoId]; [editJson] is an [com.dimroom.domain.model.EditSidecar]. */
    suspend fun saveEditSidecar(photoId: String, editJson: String): StorageResult

    /** Reads the raw sidecar JSON for [photoId], or null when the photo has never been edited. */
    suspend fun loadEditSidecar(photoId: String): String?

    /** Deletes the object identified by [remoteId]. Deleting something already gone is a success. */
    suspend fun deletePhoto(remoteId: String): StorageResult

    /** Creates [path] and any missing parents. Existing folders are a success. */
    suspend fun createFolder(path: String): StorageResult

    /** Human-readable name shown in the Storage settings section. */
    fun getProviderName(): String

    /** False until the backend has credentials/configuration and can serve requests. */
    fun isConfigured(): Boolean

    companion object {
        const val ORIGINALS_ROOT = "Originals"
        const val EDITS_ROOT = "Edits"
        const val PREVIEWS_ROOT = "Previews"

        /** Album folder used for photos that are not in any user album. */
        const val DEFAULT_ALBUM_FOLDER = "All Photos"

        fun originalPath(albumFolder: String, photoId: String): String =
            "$ORIGINALS_ROOT/$albumFolder/$photoId.jpg"

        fun sidecarPath(photoId: String): String = "$EDITS_ROOT/$photoId.json"

        fun thumbnailPath(photoId: String): String = "$PREVIEWS_ROOT/${photoId}_thumb.jpg"
    }
}

/**
 * Metadata for one stored photo. Deliberately primitive-only: a cloud provider can populate every
 * field from a listing response without downloading bytes.
 */
data class PhotoMetadata(
    /** Provider-scoped identifier. For [LocalStorageProvider] this is the relative path. */
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
)

/** Result of a storage mutation. */
sealed interface StorageResult {

    /** [id] is the provider-scoped identifier of the object that was written. */
    data class Success(val id: String, val path: String) : StorageResult

    data class Failure(val message: String, val cause: Throwable? = null) : StorageResult

    /** Returned by not-yet-implemented backends instead of throwing into a coroutine. */
    data object NotSupported : StorageResult

    val isSuccess: Boolean get() = this is Success

    companion object {
        /** Wraps a block, converting any thrown IO problem into a [Failure]. */
        inline fun runCatchingResult(id: String, path: String, block: () -> Unit): StorageResult = try {
            block()
            Success(id, path)
        } catch (t: Throwable) {
            Failure(t.message ?: t::class.java.simpleName, t)
        }
    }
}
