package com.emilio.ytmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.emilio.ytmusic.storage.StorageManager
import com.emilio.ytmusic.ui.components.EqualizerModal
import com.emilio.ytmusic.ui.components.MiniPlayer
import com.emilio.ytmusic.ui.components.NowPlayingSheet
import com.emilio.ytmusic.ui.components.StorageSetupDialog
import com.emilio.ytmusic.ui.theme.*
import com.emilio.ytmusic.viewmodel.ExploreViewModel
import com.emilio.ytmusic.viewmodel.LibraryViewModel
import com.emilio.ytmusic.viewmodel.PlayerViewModel

enum class NavTab {
    EXPLORE, LIBRARY, WEB_PLAYER, SETTINGS
}

@Composable
fun MainScreen(
    exploreViewModel: ExploreViewModel,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel
) {
    val context = LocalContext.current
    val storageManager = remember { StorageManager.getInstance(context) }
    var showStorageSetupDialog by remember { mutableStateOf(storageManager.isFirstLaunch()) }

    var activeTab by remember { mutableStateOf(NavTab.EXPLORE) }
    val playerUiState by playerViewModel.uiState.collectAsState()
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val webState by com.emilio.ytmusic.web.WebMusicDownloader.state.collectAsState()
    val activeAudioSource by com.emilio.ytmusic.service.PlaybackCoordinator.activeSource.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xF2101012),
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = activeTab == NavTab.EXPLORE,
                        onClick = { activeTab = NavTab.EXPLORE },
                        icon = { Icon(Icons.Default.Explore, contentDescription = "Explorar") },
                        label = { Text("Explorar") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppleRose,
                            selectedTextColor = AppleRose,
                            indicatorColor = Color(0x26FA2D48),
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                    NavigationBarItem(
                        selected = activeTab == NavTab.LIBRARY,
                        onClick = { activeTab = NavTab.LIBRARY },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Biblioteca") },
                        label = { Text("Biblioteca") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppleRose,
                            selectedTextColor = AppleRose,
                            indicatorColor = Color(0x26FA2D48),
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                    NavigationBarItem(
                        selected = activeTab == NavTab.WEB_PLAYER,
                        onClick = { activeTab = NavTab.WEB_PLAYER },
                        icon = { Icon(Icons.Default.Language, contentDescription = "Web Music") },
                        label = { Text("Web Music") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppleRose,
                            selectedTextColor = AppleRose,
                            indicatorColor = Color(0x26FA2D48),
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                    NavigationBarItem(
                        selected = activeTab == NavTab.SETTINGS,
                        onClick = { activeTab = NavTab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                        label = { Text("Ajustes") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppleRose,
                            selectedTextColor = AppleRose,
                            indicatorColor = Color(0x26FA2D48),
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            },
            containerColor = DarkBackground
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (activeTab) {
                    NavTab.EXPLORE -> ExploreScreen(viewModel = exploreViewModel)
                    NavTab.LIBRARY -> LibraryScreen(
                        viewModel = libraryViewModel,
                        shuffleMode = playerUiState.shuffleMode,
                        onTrackSelected = { track ->
                            playerViewModel.playTrack(track, libraryUiState.tracks)
                        },
                        onStartShuffle = { tracks, mode ->
                            playerViewModel.startWithShuffleMode(tracks, mode)
                        }
                    )
                    NavTab.WEB_PLAYER -> WebMusicScreen(
                        playerViewModel = playerViewModel,
                        libraryViewModel = libraryViewModel
                    )
                    NavTab.SETTINGS -> SettingsScreen(
                        onFolderChanged = { libraryViewModel.refreshLibrary() }
                    )
                }

                // MiniPlayer unificado (Local o Web) fuera de Web Music para no obstruir los controles del navegador
                val hasActiveMedia = (activeAudioSource == com.emilio.ytmusic.service.AudioSource.WEB && webState.currentTrack != null) ||
                    playerUiState.currentTrack != null

                if (activeTab != NavTab.WEB_PLAYER && hasActiveMedia) {
                    MiniPlayer(
                        activeSource = activeAudioSource,
                        localState = playerUiState,
                        webState = webState,
                        onTogglePlayPause = {
                            if (activeAudioSource == com.emilio.ytmusic.service.AudioSource.WEB) {
                                com.emilio.ytmusic.web.WebMusicDownloader.toggleWebPlayPause()
                            } else {
                                playerViewModel.togglePlayPause()
                            }
                        },
                        onNext = {
                            if (activeAudioSource == com.emilio.ytmusic.service.AudioSource.WEB) {
                                com.emilio.ytmusic.web.WebMusicDownloader.nextWebTrack()
                            } else {
                                playerViewModel.playNext()
                            }
                        },
                        onClick = {
                            if (activeAudioSource == com.emilio.ytmusic.service.AudioSource.WEB) {
                                activeTab = NavTab.WEB_PLAYER
                            } else {
                                playerViewModel.toggleSheet(true)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }

        // Hoja expandida de reproducción
        if (playerUiState.isSheetExpanded && playerUiState.currentTrack != null) {
            NowPlayingSheet(
                uiState = playerUiState,
                onTogglePlayPause = { playerViewModel.togglePlayPause() },
                onPrevious = { playerViewModel.playPrevious() },
                onNext = { playerViewModel.playNext() },
                onSeekTo = { playerViewModel.seekTo(it) },
                onCycleShuffle = { playerViewModel.cycleShuffleMode() },
                onToggleLyrics = { playerViewModel.toggleLyrics(!playerUiState.showLyrics) },
                onToggleEqualizer = { playerViewModel.toggleEqualizer(!playerUiState.showEqualizer) },
                onDismiss = { playerViewModel.toggleSheet(false) }
            )
        }

        // Modal de ecualizador
        if (playerUiState.showEqualizer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                EqualizerModal(
                    uiState = playerUiState,
                    onPresetSelected = { playerViewModel.applyPreset(it) },
                    onBassBoostChanged = { playerViewModel.setBassBoost(it) },
                    onDismiss = { playerViewModel.toggleEqualizer(false) }
                )
            }
        }
        // Diálogo de configuración inicial de carpeta y permisos para Android 16
        if (showStorageSetupDialog) {
            StorageSetupDialog(
                onDismiss = { showStorageSetupDialog = false },
                onFolderSelected = {
                    libraryViewModel.refreshLibrary()
                }
            )
        }
    }
}
