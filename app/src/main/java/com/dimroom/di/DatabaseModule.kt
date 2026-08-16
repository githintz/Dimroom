package com.dimroom.di

import android.content.Context
import androidx.room.Room
import com.dimroom.data.db.AlbumDao
import com.dimroom.data.db.DimroomDatabase
import com.dimroom.data.db.EditDao
import com.dimroom.data.db.CompositeDao
import com.dimroom.data.db.PhotoDao
import com.dimroom.data.db.PresetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DimroomDatabase =
        Room.databaseBuilder(context, DimroomDatabase::class.java, DimroomDatabase.NAME)
            .addMigrations(DimroomDatabase.MIGRATION_1_2, DimroomDatabase.MIGRATION_2_3)
            // Last-resort net for a version with no migration path. Room is a cache over the
            // on-disk library — originals and edit sidecars are the source of truth — so this
            // costs album membership and HDR grouping at worst, never photos.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideCompositeDao(db: DimroomDatabase): CompositeDao = db.compositeDao()

    @Provides
    fun providePhotoDao(db: DimroomDatabase): PhotoDao = db.photoDao()

    @Provides
    fun provideAlbumDao(db: DimroomDatabase): AlbumDao = db.albumDao()

    @Provides
    fun provideEditDao(db: DimroomDatabase): EditDao = db.editDao()

    @Provides
    fun providePresetDao(db: DimroomDatabase): PresetDao = db.presetDao()
}
