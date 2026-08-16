package com.dimroom.data.storage

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scaffold for a Google Drive backend. Intentionally empty — this phase ships no OAuth, no API keys
 * and no network calls.
 *
 * A future PR fills these methods in and flips one binding in
 * [com.dimroom.di.StorageModule]; nothing above the interface changes. The Drive folder is expected
 * to mirror the layout documented on [StorageProvider], with `remoteId` becoming a Drive file id
 * rather than a relative path.
 */
@Singleton
class GoogleDriveStorageProvider @Inject constructor() : StorageProvider {

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

    /** Never configured while the backend is a stub, which is what keeps the UI showing "Coming soon". */
    override fun isConfigured(): Boolean = false

    companion object {
        const val PROVIDER_NAME = "Google Drive"
        private const val COMING_SOON = "Coming soon"
    }
}
