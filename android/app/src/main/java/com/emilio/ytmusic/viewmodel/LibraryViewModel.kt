package com.emilio.ytmusic.viewmodel

import android.app.Application
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.ytmusic_core.LocalTrack
import uniffi.ytmusic_core.ytmScanLibrary
import java.io.File
import java.text.Normalizer

data class LibraryUiState(
    val tracks: List<LocalTrack> = emptyList(),
    val isScanning: Boolean = false,
    val duplicateCount: Int = 0,
    val cleanupMessage: String? = null
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        refreshLibrary()
    }

    fun clearCleanupMessage() {
        _uiState.value = _uiState.value.copy(cleanupMessage = null)
    }

    fun refreshLibrary() {
        _uiState.value = _uiState.value.copy(isScanning = true)
        viewModelScope.launch(Dispatchers.IO) {
            val storageManager = com.emilio.ytmusic.storage.StorageManager.getInstance(getApplication())
            val chosenDir = File(storageManager.getDownloadFolderPath())
            val appMusicDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

            // Limpiar archivos .jpg sueltos generados por versiones anteriores dentro de Biblioteca/ para sacarlos de la Galería
            cleanupLooseCoversFromGallery(File(chosenDir, "Biblioteca"))
            if (publicMusicDir != null) {
                cleanupLooseCoversFromGallery(File(publicMusicDir, "YTMusic/Biblioteca"))
                cleanupLooseCoversFromGallery(File(publicMusicDir, "Biblioteca"))
            }

            val trackList = mutableListOf<LocalTrack>()
            val scannedPaths = mutableSetOf<String>()

            // 1. Escanear carpeta configurada por el usuario
            if (chosenDir.exists() && chosenDir.canRead()) {
                val chosenTracks = ytmScanLibrary(chosenDir.absolutePath)
                for (t in chosenTracks) {
                    if (scannedPaths.add(t.filePath)) {
                        trackList.add(t)
                    }
                }
            }

            // 2. Escanear carpeta pública estándar si es distinta
            if (publicMusicDir != null && publicMusicDir.exists() && publicMusicDir.canRead() && publicMusicDir.absolutePath != chosenDir.absolutePath) {
                val publicTracks = ytmScanLibrary(publicMusicDir.absolutePath)
                for (pt in publicTracks) {
                    if (scannedPaths.add(pt.filePath)) {
                        trackList.add(pt)
                    }
                }
            }

            // 3. Escanear almacenamiento privado de la app
            if (appMusicDir != null && appMusicDir.exists() && appMusicDir.absolutePath != chosenDir.absolutePath) {
                val appTracks = ytmScanLibrary(appMusicDir.absolutePath)
                for (at in appTracks) {
                    if (scannedPaths.add(at.filePath)) {
                        trackList.add(at)
                    }
                }
            }

            trackList.sortBy { it.artist.lowercase() }

            val dupes = findDuplicateTracks(trackList)

            _uiState.value = _uiState.value.copy(
                tracks = trackList,
                isScanning = false,
                duplicateCount = dupes.size
            )
        }
    }

    /**
     * Elimina canciones duplicadas (incluso en diferentes formatos como .opus vs .m4a vs .mp3),
     * conservando automáticamente la versión de mayor calidad (FLAC > M4A > OPUS > MP3 > mayor tamaño).
     */
    fun deduplicateLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentTracks = _uiState.value.tracks
            val dupesToRemove = findDuplicateTracks(currentTracks)
            if (dupesToRemove.isEmpty()) {
                _uiState.value = _uiState.value.copy(duplicateCount = 0)
                return@launch
            }

            val resolver = getApplication<Application>().contentResolver
            var removedCount = 0

            for (dupe in dupesToRemove) {
                try {
                    val f = File(dupe.filePath)
                    if (f.exists() && f.delete()) {
                        removedCount++
                        // Eliminar también de MediaStore para que no queden registros fantasma
                        try {
                            resolver.delete(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                "${MediaStore.Audio.Media.DATA} = ?",
                                arrayOf(dupe.filePath)
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            _uiState.value = _uiState.value.copy(
                cleanupMessage = if (removedCount > 0) {
                    "Se eliminaron $removedCount duplicados (conservando la mejor calidad)."
                } else {
                    "No se pudieron eliminar algunos archivos duplicados."
                }
            )
            refreshLibrary()
        }
    }

    private fun findDuplicateTracks(tracks: List<LocalTrack>): List<LocalTrack> {
        val groups = tracks.groupBy { duplicateKeyFor(it) }
        val toRemove = mutableListOf<LocalTrack>()
        for ((key, group) in groups) {
            if (key.isBlank() || group.size <= 1) continue
            // Ordenar por prioridad de formato (FLAC > M4A > OPUS > MP3 > resto) y luego mayor tamaño
            val sorted = group.sortedWith(
                compareByDescending<LocalTrack> { formatPriority(it.format) }
                    .thenByDescending { it.sizeBytes }
            )
            // Conservar sorted.first(), marcar el resto como duplicados
            toRemove.addAll(sorted.drop(1))
        }
        return toRemove
    }

    private fun formatPriority(format: String): Int = when (format.lowercase()) {
        "flac" -> 5
        "m4a" -> 4
        "opus" -> 3
        "mp3" -> 2
        else -> 1
    }

    private fun duplicateKeyFor(track: LocalTrack): String {
        val normTitle = normalizeText(
            track.title.ifBlank { File(track.filePath).nameWithoutExtension }
        )
        val primaryArtist = extractPrimaryArtist(track.artist)
        val normArtist = normalizeText(primaryArtist)
        if (normTitle.isBlank()) return ""
        return "$normArtist|$normTitle"
    }

    private fun extractPrimaryArtist(artist: String): String {
        val lower = artist.lowercase()
        var idx = artist.length
        for (sep in listOf(",", " & ", " feat. ", " ft. ", " featuring ", " x ", " y ")) {
            val found = lower.indexOf(sep)
            if (found in 1 until idx) idx = found
        }
        return artist.substring(0, idx).trim()
    }

    private fun normalizeText(input: String): String {
        val stripped = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "") // quitar (Official Audio), [Remastered], etc.
            .replace(Regex("[^a-z0-9]"), "")
        return stripped
    }

    /**
     * Elimina carátulas .jpg sueltas (cover.jpg o <cancion>.jpg) dentro de carpetas Biblioteca/
     * y purga su entrada de MediaStore para que desaparezcan de la app Galería.
     */
    private fun cleanupLooseCoversFromGallery(bibliotecaDir: File) {
        if (!bibliotecaDir.exists() || !bibliotecaDir.isDirectory) return
        val audioExts = setOf("opus", "m4a", "mp3", "flac", "ogg", "aac")
        val resolver = getApplication<Application>().contentResolver
        try {
            bibliotecaDir.walkTopDown().forEach { file ->
                if (file.isFile && file.extension.equals("jpg", ignoreCase = true)) {
                    val parent = file.parentFile ?: return@forEach
                    val isAlbumCover = file.name.equals("cover.jpg", ignoreCase = true) ||
                        file.name.equals("folder.jpg", ignoreCase = true)
                    val hasMatchingAudio = audioExts.any { ext ->
                        File(parent, "${file.nameWithoutExtension}.$ext").exists()
                    }
                    if (isAlbumCover || hasMatchingAudio) {
                        val absPath = file.absolutePath
                        if (file.delete()) {
                            try {
                                resolver.delete(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    "${MediaStore.Images.Media.DATA} = ?",
                                    arrayOf(absPath)
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
