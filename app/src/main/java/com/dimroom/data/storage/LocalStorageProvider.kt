package com.dimroom.data.storage

import android.graphics.BitmapFactory
import com.dimroom.di.IoDispatcher
import com.dimroom.di.LibraryRoot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StorageProvider] backed by app-private storage.
 *
 * [root] is the provider's own directory (`filesDir/library`); every path crossing the interface is
 * resolved beneath it and validated, so a malformed path can never escape the sandbox.
 */
@Singleton
class LocalStorageProvider @Inject constructor(
    @LibraryRoot private val root: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : StorageProvider {

    override suspend fun listPhotos(folderPath: String): List<PhotoMetadata> = withContext(ioDispatcher) {
        val dir = resolve(folderPath) ?: return@withContext emptyList()
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name }
            .map { file -> file.toMetadata(relativePathOf(file)) }
    }

    override suspend fun uploadPhoto(localFile: File, targetPath: String): StorageResult =
        withContext(ioDispatcher) {
            val target = resolve(targetPath)
                ?: return@withContext StorageResult.Failure("Illegal target path: $targetPath")
            StorageResult.runCatchingResult(targetPath, targetPath) {
                target.parentFile?.mkdirs()
                localFile.copyTo(target, overwrite = true)
            }
        }

    override suspend fun downloadPhoto(remoteId: String, targetLocalFile: File): StorageResult =
        withContext(ioDispatcher) {
            val source = resolve(remoteId)
                ?: return@withContext StorageResult.Failure("Illegal source path: $remoteId")
            if (!source.isFile) return@withContext StorageResult.Failure("No such photo: $remoteId")
            StorageResult.runCatchingResult(remoteId, targetLocalFile.absolutePath) {
                targetLocalFile.parentFile?.mkdirs()
                source.copyTo(targetLocalFile, overwrite = true)
            }
        }

    override suspend fun saveEditSidecar(photoId: String, editJson: String): StorageResult =
        withContext(ioDispatcher) {
            val path = StorageProvider.sidecarPath(photoId)
            val target = resolve(path)
                ?: return@withContext StorageResult.Failure("Illegal sidecar path: $path")
            StorageResult.runCatchingResult(path, path) {
                target.parentFile?.mkdirs()
                // Write-then-rename so a crash mid-write can't leave a truncated sidecar behind.
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeText(editJson)
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
        }

    override suspend fun loadEditSidecar(photoId: String): String? = withContext(ioDispatcher) {
        val file = resolve(StorageProvider.sidecarPath(photoId)) ?: return@withContext null
        if (!file.isFile) return@withContext null
        runCatching { file.readText() }.getOrNull()
    }

    override suspend fun deletePhoto(remoteId: String): StorageResult = withContext(ioDispatcher) {
        val target = resolve(remoteId)
            ?: return@withContext StorageResult.Failure("Illegal path: $remoteId")
        StorageResult.runCatchingResult(remoteId, remoteId) {
            if (target.exists() && !target.deleteRecursively()) {
                error("Could not delete $remoteId")
            }
        }
    }

    override suspend fun createFolder(path: String): StorageResult = withContext(ioDispatcher) {
        val dir = resolve(path)
            ?: return@withContext StorageResult.Failure("Illegal folder path: $path")
        StorageResult.runCatchingResult(path, path) {
            if (!dir.isDirectory && !dir.mkdirs()) {
                error("Could not create folder $path")
            }
        }
    }

    override fun getProviderName(): String = "On this device"

    /** Local storage is always available. */
    override fun isConfigured(): Boolean = true

    // -- Local-only helpers -------------------------------------------------------------------
    // Callers that specifically need a filesystem path (Coil, the GL texture loader, the exporter)
    // use these; the generic repository code stays on the interface above.

    /** Absolute file for a provider-relative [path], or null when the path escapes the sandbox. */
    fun fileFor(path: String): File? = resolve(path)

    /** Total bytes currently held under [subPath]. */
    suspend fun sizeOf(subPath: String): Long = withContext(ioDispatcher) {
        val dir = resolve(subPath) ?: return@withContext 0L
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** Empties a subtree but keeps the folder itself, used by cache management in Settings. */
    suspend fun clear(subPath: String): StorageResult = withContext(ioDispatcher) {
        val dir = resolve(subPath) ?: return@withContext StorageResult.Failure("Illegal path: $subPath")
        StorageResult.runCatchingResult(subPath, subPath) {
            dir.listFiles().orEmpty().forEach { it.deleteRecursively() }
            dir.mkdirs()
        }
    }

    private fun relativePathOf(file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)

    /**
     * Resolves a provider-relative path against [root], rejecting anything that would traverse out
     * of the sandbox (`..`, absolute paths, symlink escapes).
     */
    private fun resolve(path: String): File? {
        val cleaned = path.trim().trim('/')
        if (cleaned.isEmpty()) return root
        if (cleaned.split('/').any { it == ".." }) return null
        val candidate = File(root, cleaned)
        val canonicalRoot = root.canonicalPath
        val canonicalCandidate = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        return if (canonicalCandidate == canonicalRoot ||
            canonicalCandidate.startsWith(canonicalRoot + File.separator)
        ) {
            candidate
        } else {
            null
        }
    }

    private fun File.toMetadata(relativePath: String): PhotoMetadata {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(absolutePath, bounds) }
        return PhotoMetadata(
            id = relativePath,
            name = name,
            path = relativePath,
            sizeBytes = length(),
            lastModifiedMs = lastModified(),
            mimeType = if (extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg",
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
        )
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
