package com.emilio.ytmusic.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.emilio.ytmusic.service.AudioSource
import com.emilio.ytmusic.ui.theme.*
import com.emilio.ytmusic.viewmodel.PlayerUiState
import com.emilio.ytmusic.web.WebDownloadState

/**
 * MiniPlayer unificado (Apple Design) que muestra en armonía el reproductor activo:
 * - Si está sonando la Biblioteca Local: carátula local, progreso, Play/Pausa, Siguiente y acceso a NowPlayingSheet.
 * - Si está sonando Web Music: carátula web, insignia WEB, Play/Pausa, Siguiente y regreso instantáneo a Web Music.
 */
@Composable
fun MiniPlayer(
    activeSource: AudioSource,
    localState: PlayerUiState,
    webState: WebDownloadState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isWebActive = activeSource == AudioSource.WEB && webState.currentTrack != null
    val localTrack = localState.currentTrack
    val webTrack = webState.currentTrack

    if (!isWebActive && localTrack == null) return

    val title = if (isWebActive) webTrack!!.title else localTrack!!.title
    val subtitle = if (isWebActive) webTrack!!.artist else "${localTrack!!.artist} • ${localTrack.format.uppercase()}"
    val isPlaying = if (isWebActive) webState.isPlaying else localState.isPlaying

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(20.dp),
                    spotColor = Color(0x66000000),
                    ambientColor = Color(0x33000000)
                )
                .clickable { onClick() },
            shape = RoundedCornerShape(20.dp),
            color = Color(0xF21C1C20),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x2EFFFFFF))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Carátula (Web o Local)
                    if (isWebActive) {
                        AsyncImage(
                            model = webTrack!!.coverUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x26FFFFFF))
                        )
                    } else {
                        TrackArtwork(
                            track = localTrack!!,
                            modifier = Modifier.size(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            placeholderIconSize = 22.dp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isWebActive) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0x3300C7BE)
                                ) {
                                    Text(
                                        text = "WEB",
                                        color = AppleCyan,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Botón táctil Play/Pause
                    Surface(
                        onClick = onTogglePlayPause,
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x26FFFFFF),
                        modifier = Modifier
                            .size(38.dp)
                            .applePressEffect()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                tint = TextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Botón táctil Siguiente
                    Surface(
                        onClick = onNext,
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x1AFFFFFF),
                        modifier = Modifier
                            .size(34.dp)
                            .applePressEffect()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Siguiente",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Barra de progreso inferior fina integrada (Local) o indicador de actividad (Web)
                if (!isWebActive && localState.durationMs > 0) {
                    val progress = (localState.positionMs.toFloat() / localState.durationMs.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
                        color = AppleRose,
                        trackColor = Color(0x1AFFFFFF)
                    )
                } else if (isWebActive && isPlaying) {
                    LinearProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
                        color = AppleCyan,
                        trackColor = Color(0x1AFFFFFF)
                    )
                }
            }
        }
    }
}
