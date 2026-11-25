package com.emilio.ytmusic.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilio.ytmusic.ui.theme.*
import com.emilio.ytmusic.viewmodel.PlayerUiState
import com.emilio.ytmusic.viewmodel.ShuffleMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    uiState: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleShuffle: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onDismiss: () -> Unit
) {
    val track = uiState.currentTrack ?: return

    // Escala elástica física al estilo Apple Music: la carátula se expande al reproducir y se contrae ligeramente al pausar
    val artworkScale by animateFloatAsState(
        targetValue = if (uiState.isPlaying) 1.0f else 0.88f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f),
        label = "artworkScale"
    )

    val shadowElevation by animateFloatAsState(
        targetValue = if (uiState.isPlaying) 20f else 6f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f),
        label = "shadowElevation"
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0D0D10),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = Color(0x66FFFFFF),
                width = 40.dp,
                height = 4.dp
            )
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x26FA2D48),
                            Color(0xFF0D0D10),
                            Color(0xFF09090B)
                        )
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cabecera superior con botón minimizar y título de álbum
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimizar",
                        tint = TextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    text = track.album.ifBlank { "YouTube Music" },
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                IconButton(onClick = onToggleEqualizer) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Ecualizador",
                        tint = if (uiState.showEqualizer) AppleRose else TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Centro: Carátula interactiva con física o Vista de Letras sincronizadas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.showLyrics) {
                    LyricsView(
                        lyricsData = uiState.lyrics,
                        currentLyricIndex = uiState.currentLyricIndex,
                        onSeekTo = onSeekTo
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = artworkScale
                                scaleY = artworkScale
                            }
                            .shadow(
                                elevation = shadowElevation.dp,
                                shape = RoundedCornerShape(26.dp),
                                spotColor = Color(0x66FA2D48),
                                ambientColor = Color(0x33FA2D48)
                            )
                    ) {
                        TrackArtwork(
                            track = track,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f),
                            shape = RoundedCornerShape(26.dp),
                            placeholderIconSize = 72.dp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Información de la pista (Título, Artista y Etiqueta de Formato)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.artist,
                        color = AppleRose,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    onClick = onCycleShuffle,
                    shape = RoundedCornerShape(10.dp),
                    color = when (uiState.shuffleMode) {
                        ShuffleMode.OFF -> Color(0x1AFFFFFF)
                        ShuffleMode.SHUFFLE -> Color(0x33FA2D48)
                        ShuffleMode.SMART_SHUFFLE -> Color(0x3300C7BE)
                    },
                    modifier = Modifier.applePressEffect()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (uiState.shuffleMode == ShuffleMode.SMART_SHUFFLE) Icons.Default.AutoAwesome else Icons.Default.Shuffle,
                            contentDescription = "Modo Aleatorio",
                            tint = when (uiState.shuffleMode) {
                                ShuffleMode.OFF -> TextMuted
                                ShuffleMode.SHUFFLE -> AppleRose
                                ShuffleMode.SMART_SHUFFLE -> AppleCyan
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when (uiState.shuffleMode) {
                                ShuffleMode.OFF -> track.format.uppercase()
                                ShuffleMode.SHUFFLE -> "Aleatorio"
                                ShuffleMode.SMART_SHUFFLE -> "Inteligente"
                            },
                            color = when (uiState.shuffleMode) {
                                ShuffleMode.OFF -> TextSecondary
                                ShuffleMode.SHUFFLE -> AppleRose
                                ShuffleMode.SMART_SHUFFLE -> AppleCyan
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Barra de progreso interactiva fluida (sin tirones al arrastrar)
            var isDragging by remember { mutableStateOf(false) }
            var dragFraction by remember { mutableFloatStateOf(0f) }

            val liveProgress = if (uiState.durationMs > 0) {
                (uiState.positionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            val displayProgress = if (isDragging) dragFraction else liveProgress
            val displayPosMs = if (isDragging) (dragFraction * uiState.durationMs).toLong() else uiState.positionMs

            Slider(
                value = displayProgress,
                onValueChange = { frac ->
                    isDragging = true
                    dragFraction = frac
                },
                onValueChangeFinished = {
                    onSeekTo((dragFraction * uiState.durationMs).toLong())
                    isDragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = TextPrimary,
                    activeTrackColor = TextPrimary,
                    inactiveTrackColor = Color(0x33FFFFFF)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(displayPosMs),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "-${formatMs((uiState.durationMs - displayPosMs).coerceAtLeast(0L))}",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controles de transporte con botón principal de gran tamaño
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Alternar Letras
                IconButton(onClick = onToggleLyrics) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Letras",
                        tint = if (uiState.showLyrics) AppleRose else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Canción anterior
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Anterior",
                        tint = TextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Botón Play / Pause gigante con respuesta física
                Surface(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    color = TextPrimary,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .size(72.dp)
                        .applePressEffect()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pausar" else "Reproducir",
                            tint = OLEDBlack,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                // Canción siguiente
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Siguiente",
                        tint = TextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Ecualizador DSP
                IconButton(onClick = onToggleEqualizer) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Ecualizador",
                        tint = if (uiState.showEqualizer) AppleRose else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

fun formatMs(ms: Long): String {
    val totalSecs = ms / 1000
    val minutes = totalSecs / 60
    val seconds = totalSecs % 60
    return String.format("%d:%02d", minutes, seconds)
}
