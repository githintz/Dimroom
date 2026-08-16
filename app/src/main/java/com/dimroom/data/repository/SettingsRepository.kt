package com.dimroom.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dimroom.data.storage.LocalStorageProvider
import com.dimroom.data.storage.StorageProvider
import com.dimroom.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** App theme preference. */
enum class ThemeMode(val displayName: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localProvider: LocalStorageProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_THEME]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME] = mode.name }
    }

    /** Bytes held by regenerable previews plus Coil's own disk cache. */
    suspend fun cacheSizeBytes(): Long = withContext(ioDispatcher) {
        val previews = localProvider.sizeOf(StorageProvider.PREVIEWS_ROOT)
        val coil = context.cacheDir.resolve("image_cache")
            .walkBottomUp()
            .filter { it.isFile }
            .sumOf { it.length() }
        previews + coil
    }

    /**
     * Clears previews and the image cache. Originals and edit sidecars are never touched; the grid
     * falls back to downsampling originals for any thumbnail that is no longer on disk.
     */
    suspend fun clearCaches() = withContext(ioDispatcher) {
        localProvider.clear(StorageProvider.PREVIEWS_ROOT)
        context.cacheDir.resolve("image_cache").deleteRecursively()
        Unit
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
    }
}
