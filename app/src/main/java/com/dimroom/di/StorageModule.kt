package com.dimroom.di

import com.dimroom.data.storage.LocalStorageProvider
import com.dimroom.data.storage.StorageProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one-line swap point for storage backends.
 *
 * Repositories inject [StorageProvider] and nothing else, so pointing the app at a cloud backend is
 * a matter of changing the bound implementation here (or making this binding read a user
 * preference) once [com.dimroom.data.storage.GoogleDriveStorageProvider] or
 * [com.dimroom.data.storage.OpenDriveStorageProvider] is filled in.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindStorageProvider(local: LocalStorageProvider): StorageProvider
}
