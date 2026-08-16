package com.dimroom.data.storage

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scaffold for an OpenDrive backend. Intentionally empty — this phase ships no OAuth, no API keys
 * and no network calls.
 *
 * See [GoogleDriveStorageProvider] for the wiring a future PR needs to touch.
 */
@Singleton
class OpenDriveStorageProvider @Inject constructor() : StorageProvider {

    override suspend fun listPhotos(folderPath: String): List<PhotoMetadata> =
        throw NotImplementedError(COMING_SOON)

    override suspend fun uploadPhoto(localFile: File, targetPath: String): StorageResult =
        throw NotImplementedError(COMING_SOON)

    override suspend fun downloadPhoto(remoteId: String, targetLocalFile: File): StorageResult =
        throw NotImplementedError(COMING_SOON)

    override suspend fun saveEditSidecar(photoId: String, editJson: String): StorageResult =
        throw NotImplementedError(COMING_SOON)

    override suspend fun loadEditSidecar(photoId: String): String? =
        throw NotImplementedError(COMING_SOON)

    override suspend fun deletePhoto(remoteId: String): StorageResult =
        throw NotImplementedError(COMING_SOON)

    override suspend fun createFolder(path: String): StorageResult =
        throw NotImplementedError(COMING_SOON)

    override fun getProviderName(): String = PROVIDER_NAME

    override fun isConfigured(): Boolean = false

    companion object {
        const val PROVIDER_NAME = "OpenDrive"
        private const val COMING_SOON = "Coming soon"
    }
}
