package com.dimroom.di

import android.content.Context
import com.dimroom.data.storage.StorageProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Root of the local library. Everything the app owns lives beneath this single directory, which
     * keeps the "one folder, three subfolders" layout in [StorageProvider] literally true on disk
     * and makes the local tree trivially mirrorable to a cloud folder later.
     */
    @Provides
    @Singleton
    @LibraryRoot
    fun provideLibraryRoot(@ApplicationContext context: Context): File =
        File(context.filesDir, "library").apply { mkdirs() }
}
