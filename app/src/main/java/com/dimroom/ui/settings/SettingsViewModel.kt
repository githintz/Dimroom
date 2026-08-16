package com.dimroom.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dimroom.data.repository.PhotoRepository
import com.dimroom.data.repository.SettingsRepository
import com.dimroom.data.repository.ThemeMode
import com.dimroom.data.storage.GoogleDriveStorageProvider
import com.dimroom.data.storage.OpenDriveStorageProvider
import com.dimroom.data.storage.StorageProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row in the Storage section. */
data class StorageBackend(
    val name: String,
    val isActive: Boolean,
    val isConfigured: Boolean,
)

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val photoCount: Int = 0,
    val cacheBytes: Long = 0,
    val isClearingCache: Boolean = false,
    val activeProviderName: String = "",
    val backends: List<StorageBackend> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val photoRepository: PhotoRepository,
    private val storageProvider: StorageProvider,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.themeMode,
        photoRepository.observePhotoCount(),
        local,
    ) { theme, photoCount, localState ->
        SettingsUiState(
            themeMode = theme,
            photoCount = photoCount,
            cacheBytes = localState.cacheBytes,
            isClearingCache = localState.isClearingCache,
            activeProviderName = storageProvider.getProviderName(),
            backends = backends(),
            message = localState.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshCacheSize()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun clearCaches() {
        viewModelScope.launch {
            local.update { it.copy(isClearingCache = true) }
            settingsRepository.clearCaches()
            local.update {
                it.copy(isClearingCache = false, cacheBytes = 0, message = "Cache cleared")
            }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val bytes = settingsRepository.cacheSizeBytes()
            local.update { it.copy(cacheBytes = bytes) }
        }
    }

    fun consumeMessage() = local.update { it.copy(message = null) }

    /**
     * The Storage section reads its rows straight off the providers, so a backend becomes live in
     * the UI the moment its `isConfigured()` starts returning true — no extra plumbing needed.
     */
    private fun backends(): List<StorageBackend> {
        val activeName = storageProvider.getProviderName()
        return listOf(
            StorageBackend(activeName, isActive = true, isConfigured = storageProvider.isConfigured()),
            StorageBackend(GoogleDriveStorageProvider.PROVIDER_NAME, isActive = false, isConfigured = false),
            StorageBackend(OpenDriveStorageProvider.PROVIDER_NAME, isActive = false, isConfigured = false),
        )
    }

    private data class LocalState(
        val cacheBytes: Long = 0,
        val isClearingCache: Boolean = false,
        val message: String? = null,
    )
}
