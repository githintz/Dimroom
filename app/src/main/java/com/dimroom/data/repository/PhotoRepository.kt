package com.dimroom.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.dimroom.data.db.AlbumDao
import com.dimroom.data.db.AlbumPhotoCrossRef
import com.dimroom.data.db.EditDao
import com.dimroom.data.db.EditStateEntity
import com.dimroom.data.db.PhotoDao
import com.dimroom.data.db.PhotoEntity
import com.dimroom.data.db.PhotoWithEditFlag
import com.dimroom.data.storage.LocalStorageProvider
import com.dimroom.data.storage.StorageProvider
import com.dimroom.data.storage.sanitizedAsFolder
import com.dimroom.data.storage.StorageResult
import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.EditSerialization
import com.dimroom.domain.model.EditSidecar
import com.dimroom.domain.model.EditStack
import com.dimroom.domain.model.Photo
import com.dimroom.domain.model.SortOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of importing a batch of picked photos. */
data class ImportSummary(val imported: Int, val failed: Int) {
    val total: Int get() = imported + failed
}

/**
 * Library reads and writes.
 *
 * Everything that touches bytes goes through [StorageProvider]; Room only holds metadata. The one
 * concession is [localProvider], used solely to turn a provider path into an absolute file for
 * components that need a real path (Coil, the GL texture loader, the exporter). When a cloud
 * backend lands, that fallback becomes "download to cache, then hand over the cached file" without
 * any change above this class.
 */
@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageProvider,
    private val localProvider: LocalStorageProvider,
    private val photoDao: PhotoDao,
    private val albumDao: AlbumDao,
    private val editDao: EditDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    fun observePhotos(albumId: String?, sortOrder: SortOrder): Flow<List<Photo>> {
        val source = if (albumId == null) photoDao.observeAll() else photoDao.observeInAlbum(albumId)
        return source.map { rows -> rows.map { it.toPhoto() }.sortedWith(sortOrder.comparator()) }
    }

    fun observePhoto(photoId: String): Flow<Photo?> =
        photoDao.observeById(photoId).map { entity -> entity?.toPhoto(hasEdits = false) }

    fun observePhotoCount(): Flow<Int> = photoDao.observeCount()

    suspend fun getPhoto(photoId: String): Photo? = withContext(ioDispatcher) {
        photoDao.findById(photoId)?.toPhoto(hasEdits = editDao.findById(photoId) != null)
    }

    /**
     * Copies picked photos into the library.
     *
     * Each photo is staged into the cache, handed to the storage provider, then thumbnailed. A
     * failure on one photo never aborts the batch.
     */
    suspend fun importPhotos(uris: List<Uri>, albumId: String?): ImportSummary = withContext(ioDispatcher) {
        val albumFolder = albumId?.let { albumDao.findById(it)?.name?.sanitizedAsFolder() }
            ?: StorageProvider.DEFAULT_ALBUM_FOLDER
        storage.createFolder("${StorageProvider.ORIGINALS_ROOT}/$albumFolder")
        storage.createFolder(StorageProvider.PREVIEWS_ROOT)
        storage.createFolder(StorageProvider.EDITS_ROOT)

        var imported = 0
        var failed = 0
        uris.forEach { uri ->
            val result = runCatching { importSingle(uri, albumId, albumFolder) }.getOrNull()
            if (result != null) imported++ else failed++
        }
        ImportSummary(imported, failed)
    }

    private suspend fun importSingle(uri: Uri, albumId: String?, albumFolder: String): PhotoEntity {
        val photoId = UUID.randomUUID().toString()
        val displayName = queryDisplayName(uri) ?: "Photo ${photoId.take(8)}"

        val staged = File(context.cacheDir, "import-$photoId.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open $uri")

        try {
            val targetPath = StorageProvider.originalPath(albumFolder, photoId)
            val upload = storage.uploadPhoto(staged, targetPath)
            if (upload !is StorageResult.Success) {
                error("Upload failed: ${(upload as? StorageResult.Failure)?.message ?: "unsupported"}")
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(staged.absolutePath, bounds)
            val orientationSwapsAxes = runCatching {
                val exif = ExifInterface(staged.absolutePath)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE,
                    -> true

                    else -> false
                }
            }.getOrDefault(false)
            val width = if (orientationSwapsAxes) bounds.outHeight else bounds.outWidth
            val height = if (orientationSwapsAxes) bounds.outWidth else bounds.outHeight
            val dateTaken = runCatching {
                ExifInterface(staged.absolutePath).let { exif ->
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?.let { ExifDates.parse(it) }
                }
            }.getOrNull() ?: System.currentTimeMillis()

            val thumbnailPath = writeThumbnail(staged, photoId)

            val entity = PhotoEntity(
                id = photoId,
                displayName = displayName,
                originalPath = targetPath,
                thumbnailPath = thumbnailPath,
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                sizeBytes = staged.length(),
                dateAddedMs = System.currentTimeMillis(),
                dateTakenMs = dateTaken,
            )
            photoDao.upsert(entity)
            if (albumId != null) {
                albumDao.addPhoto(AlbumPhotoCrossRef(albumId, photoId, System.currentTimeMillis()))
            }
            return entity
        } finally {
            staged.delete()
        }
    }

    /** Decodes a downsampled copy for the grid and stores it under `/Previews`. */
    private suspend fun writeThumbnail(source: File, photoId: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) return null

        var sampleSize = 1
        while (longestEdge / (sampleSize * 2) >= THUMBNAIL_EDGE) sampleSize *= 2

        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        val oriented = ImageOrientation.applyExif(bitmap, source.absolutePath)
        val thumbPath = StorageProvider.thumbnailPath(photoId)
        val thumbFile = localProvider.fileFor(thumbPath) ?: return null
        return try {
            thumbFile.parentFile?.mkdirs()
            thumbFile.outputStream().use { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumbPath
        } catch (t: Throwable) {
            null
        } finally {
            if (oriented !== bitmap) oriented.recycle()
            bitmap.recycle()
        }
    }

    suspend fun deletePhoto(photoId: String) = withContext(ioDispatcher) {
        val entity = photoDao.findById(photoId) ?: return@withContext
        storage.deletePhoto(entity.originalPath)
        entity.thumbnailPath?.let { storage.deletePhoto(it) }
        storage.deletePhoto(StorageProvider.sidecarPath(photoId))
        photoDao.deleteById(photoId)
    }

    // -- Edit stack ---------------------------------------------------------------------------

    fun observeEditStack(photoId: String): Flow<EditStack> =
        editDao.observeById(photoId).map { entity ->
            entity?.editJson?.let { EditSerialization.decodeStack(it) } ?: EditStack()
        }

    /**
     * Reads the edit stack, preferring Room and falling back to the sidecar on disk. The fallback is
     * what lets a library restored from a synced folder come back with its edits intact.
     */
    suspend fun getEditStack(photoId: String): EditStack = withContext(ioDispatcher) {
        editDao.findById(photoId)?.let { return@withContext EditSerialization.decodeStack(it.editJson) }
        val sidecar = storage.loadEditSidecar(photoId)?.let { EditSerialization.decodeSidecar(it) }
        sidecar?.edits ?: EditStack()
    }

    /** Persists the stack to Room and mirrors it to the portable sidecar. */
    suspend fun saveEditStack(photoId: String, stack: EditStack) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        if (stack.isIdentity) {
            editDao.deleteById(photoId)
            storage.deletePhoto(StorageProvider.sidecarPath(photoId))
            return@withContext
        }
        editDao.upsert(EditStateEntity(photoId, EditSerialization.encodeStack(stack), now))
        val sidecar = EditSidecar(
            photoId = photoId,
            originalFileName = photoDao.findById(photoId)?.displayName,
            updatedAt = now,
            edits = stack,
        )
        storage.saveEditSidecar(photoId, EditSerialization.encodeSidecar(sidecar))
    }

    // -- Local file access ---------------------------------------------------------------------

    /**
     * Best already-on-disk file to show for a photo: its thumbnail when one exists, otherwise the
     * original (which Coil downsamples itself). Cheap enough to call from composition, and the
     * fallback is what keeps the grid working after the preview cache is cleared.
     */
    fun displayFile(photo: Photo): File? =
        photo.thumbnailPath?.let { localProvider.fileFor(it) }?.takeIf { it.isFile }
            ?: localProvider.fileFor(photo.originalPath)?.takeIf { it.isFile }

    /**
     * Absolute file for a provider path, materialising it locally if needed.
     * Today the local provider already has the bytes; a cloud provider would download here.
     */
    suspend fun localFileFor(providerPath: String): File? = withContext(ioDispatcher) {
        localProvider.fileFor(providerPath)?.takeIf { it.isFile }
            ?: run {
                val cached = File(context.cacheDir, "materialised/${providerPath.substringAfterLast('/')}")
                cached.parentFile?.mkdirs()
                if (storage.downloadPhoto(providerPath, cached) is StorageResult.Success) cached else null
            }
    }

    private fun PhotoWithEditFlag.toPhoto() = Photo(
        id = id,
        displayName = displayName,
        originalPath = originalPath,
        thumbnailPath = thumbnailPath,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        dateAddedMs = dateAddedMs,
        dateTakenMs = dateTakenMs,
        hasEdits = hasEdits,
    )

    private fun PhotoEntity.toPhoto(hasEdits: Boolean) = Photo(
        id = id,
        displayName = displayName,
        originalPath = originalPath,
        thumbnailPath = thumbnailPath,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        dateAddedMs = dateAddedMs,
        dateTakenMs = dateTakenMs,
        hasEdits = hasEdits,
    )

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private companion object {
        const val THUMBNAIL_EDGE = 512
    }
}

private fun SortOrder.comparator(): Comparator<Photo> = when (this) {
    SortOrder.DATE_ADDED_DESC -> compareByDescending<Photo> { it.dateAddedMs }
    SortOrder.DATE_ADDED_ASC -> compareBy<Photo> { it.dateAddedMs }
    SortOrder.DATE_TAKEN_DESC -> compareByDescending<Photo> { it.dateTakenMs }
    SortOrder.DATE_TAKEN_ASC -> compareBy<Photo> { it.dateTakenMs }
    SortOrder.NAME_ASC -> compareBy<Photo>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
    SortOrder.NAME_DESC -> compareBy<Photo>(String.CASE_INSENSITIVE_ORDER) { it.displayName }.reversed()
}
