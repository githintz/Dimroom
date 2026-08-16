package com.dimroom.data.repository

import com.dimroom.data.db.AlbumDao
import com.dimroom.data.db.AlbumEntity
import com.dimroom.data.db.AlbumPhotoCrossRef
import com.dimroom.data.db.PhotoDao
import com.dimroom.data.storage.StorageProvider
import com.dimroom.data.storage.sanitizedAsFolder
import com.dimroom.di.IoDispatcher
import com.dimroom.domain.model.Album
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepository @Inject constructor(
    private val albumDao: AlbumDao,
    private val photoDao: PhotoDao,
    private val storage: StorageProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    fun observeAlbums(): Flow<List<Album>> = albumDao.observeAlbums().map { rows ->
        rows.map { Album(it.id, it.name, it.createdAtMs, it.photoCount, it.coverPhotoPath) }
    }

    /** Creates an album and its matching `/Originals/{name}` folder. Returns null on a name clash. */
    suspend fun createAlbum(name: String): Album? = withContext(ioDispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || albumDao.findByName(trimmed) != null) return@withContext null
        val album = AlbumEntity(UUID.randomUUID().toString(), trimmed, System.currentTimeMillis())
        albumDao.upsert(album)
        storage.createFolder("${StorageProvider.ORIGINALS_ROOT}/${trimmed.sanitizedAsFolder()}")
        Album(album.id, album.name, album.createdAtMs)
    }

    suspend fun renameAlbum(albumId: String, name: String): Boolean = withContext(ioDispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext false
        val clash = albumDao.findByName(trimmed)
        if (clash != null && clash.id != albumId) return@withContext false
        albumDao.rename(albumId, trimmed)
        true
    }

    /** Deletes the album grouping. Photos themselves are untouched and stay in the library. */
    suspend fun deleteAlbum(albumId: String) = withContext(ioDispatcher) {
        albumDao.deleteById(albumId)
    }

    suspend fun addPhotos(albumId: String, photoIds: List<String>) = withContext(ioDispatcher) {
        albumDao.addPhotos(albumId, photoIds, System.currentTimeMillis())
    }

    suspend fun addPhoto(albumId: String, photoId: String) = withContext(ioDispatcher) {
        albumDao.addPhoto(AlbumPhotoCrossRef(albumId, photoId, System.currentTimeMillis()))
    }

    suspend fun removePhoto(albumId: String, photoId: String) = withContext(ioDispatcher) {
        albumDao.removePhoto(albumId, photoId)
    }

    suspend fun albumIdsFor(photoId: String): List<String> = withContext(ioDispatcher) {
        photoDao.albumIdsFor(photoId)
    }
}
