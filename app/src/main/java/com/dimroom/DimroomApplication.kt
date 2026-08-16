package com.dimroom

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.dimroom.data.repository.PresetRepository
import com.dimroom.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DimroomApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var presetRepository: PresetRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Starter presets are upserted by stable id, so this is safe on every launch.
        applicationScope.launch { presetRepository.seedBuiltInsIfNeeded() }
    }

    /**
     * Coil is pointed at a named cache directory so Settings can measure and clear it without
     * guessing at Coil's internals.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .build()
}
