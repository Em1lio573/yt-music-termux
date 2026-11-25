package com.emilio.ytmusic.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.ytmusic_core.*
import java.io.File

data class ExploreUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<TrackMetadata> = emptyList(),
    val activeDownloads: Map<String, DownloadProgress> = emptyMap(),
    val selectedQuality: String = "native",
    val statusMessage: String? = null
)

class ExploreViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    fun onQueryChange(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
    }

    fun onQualityChange(quality: String) {
        _uiState.value = _uiState.value.copy(selectedQuality = quality)
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    fun performSearch() {
        val q = _uiState.value.query.trim()
        if (q.isEmpty()) return

        _uiState.value = _uiState.value.copy(isSearching = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = ytmSearchMusic(q)
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchResults = results
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    statusMessage = "Error al buscar: ${e.message}"
                )
            }
        }
    }

    fun startDownload(track: TrackMetadata) {
        val quality = _uiState.value.selectedQuality
        val storageManager = com.emilio.ytmusic.storage.StorageManager.getInstance(getApplication())
        val downloadPath = storageManager.getDownloadFolderPath()

        viewModelScope.launch(Dispatchers.IO) {
            val callback = object : DownloadCallback {
                override fun onProgress(progress: DownloadProgress) {
                    val currentMap = _uiState.value.activeDownloads.toMutableMap()
                    currentMap[progress.id] = progress
                    _uiState.value = _uiState.value.copy(activeDownloads = currentMap)
                }
            }

            try {
                ytmStartDownload(track, quality, downloadPath, callback)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Error al descargar: ${e.message}"
                )
            }
        }
    }
}
