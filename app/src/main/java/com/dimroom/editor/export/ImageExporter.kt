package com.dimroom.editor.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.dimroom.data.repository.ImageOrientation
import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.EditStack
import com.dimroom.domain.model.ExportOptions
import com.dimroom.editor.gl.OffscreenRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where a rendered export ended up. */
sealed interface ExportResult {
    data class SavedToGallery(val uri: Uri, val displayName: String) : ExportResult
    data class ReadyToShare(val uri: Uri, val displayName: String) : ExportResult
    data class Failed(val message: String) : ExportResult
}

/**
 * Renders the edit stack against the full-resolution original and writes the result out.
 *
 * Nothing here mutates the original: the edit stack is applied to a decoded copy on the GPU and the
 * pixels go to a brand-new file, which is what makes the whole editor non-destructive.
 */
@Singleton
class ImageExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Renders and saves into the device gallery under `Pictures/Dimroom`. */
    suspend fun exportToGallery(
        sourceFile: File,
        edits: EditStack,
        baseName: String,
        options: ExportOptions,
    ): ExportResult = withContext(ioDispatcher) {
        val rendered = renderOrNull(sourceFile, edits, options)
            ?: return@withContext ExportResult.Failed("Could not render the photo")
        try {
            val displayName = exportFileName(baseName)
            val uri = writeToMediaStore(rendered, displayName, options.quality)
                ?: return@withContext ExportResult.Failed("Could not write to the gallery")
            ExportResult.SavedToGallery(uri, displayName)
        } catch (t: Throwable) {
            ExportResult.Failed(t.message ?: "Export failed")
        } finally {
            rendered.recycle()
        }
    }

    /** Renders into app cache and returns a shareable content URI for the system share sheet. */
    suspend fun exportForShare(
        sourceFile: File,
        edits: EditStack,
        baseName: String,
        options: ExportOptions,
    ): ExportResult = withContext(ioDispatcher) {
        val rendered = renderOrNull(sourceFile, edits, options)
            ?: return@withContext ExportResult.Failed("Could not render the photo")
        try {
            val displayName = exportFileName(baseName)
            val shareDir = File(context.cacheDir, "exports").apply { mkdirs() }
            // Keep only the most recent handful of shared renders around.
            shareDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(SHARE_CACHE_LIMIT)
                ?.forEach { it.delete() }

            val target = File(shareDir, displayName)
            target.outputStream().use { out ->
                rendered.compress(Bitmap.CompressFormat.JPEG, options.quality, out)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            ExportResult.ReadyToShare(uri, displayName)
        } catch (t: Throwable) {
            ExportResult.Failed(t.message ?: "Export failed")
        } finally {
            rendered.recycle()
        }
    }

    /** Builds the chooser intent for a [ExportResult.ReadyToShare]. */
    fun shareIntent(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share photo").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun renderOrNull(sourceFile: File, edits: EditStack, options: ExportOptions): Bitmap? {
        val decoded = decodeForExport(sourceFile, options.maxDimension) ?: return null
        return try {
            OffscreenRenderer().render(decoded, edits)
        } catch (t: Throwable) {
            null
        } finally {
            decoded.recycle()
        }
    }

    /**
     * Decodes at the smallest sample size that still satisfies [maxDimension], so a 12 MP export
     * never allocates a full-size bitmap just to throw most of it away.
     */
    private fun decodeForExport(file: File, maxDimension: Int?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sampleSize = 1
        if (maxDimension != null) {
            while (longest / (sampleSize * 2) >= maxDimension) sampleSize *= 2
        }

        val raw = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        // Originals keep their camera EXIF; bake the orientation in before the GPU sees them so
        // the crop rect in the sidecar lines up with what the user framed.
        val decoded = ImageOrientation.applyExif(raw, file.absolutePath)
        if (decoded !== raw) raw.recycle()

        if (maxDimension == null) return decoded
        val decodedLongest = maxOf(decoded.width, decoded.height)
        if (decodedLongest <= maxDimension) return decoded

        val scale = maxDimension.toFloat() / decodedLongest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun writeToMediaStore(bitmap: Bitmap, displayName: String, quality: Int): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Dimroom")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            } ?: error("No output stream for $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun exportFileName(baseName: String): String {
        val stem = baseName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .ifEmpty { "Dimroom" }
        return "${stem}_dimroom_${System.currentTimeMillis()}.jpg"
    }

    private companion object {
        const val SHARE_CACHE_LIMIT = 5
    }
}
