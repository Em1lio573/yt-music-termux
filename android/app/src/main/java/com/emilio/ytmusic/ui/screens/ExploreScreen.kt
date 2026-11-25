package com.emilio.ytmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.emilio.ytmusic.ui.theme.*
import com.emilio.ytmusic.viewmodel.ExploreViewModel
import uniffi.ytmusic_core.TrackMetadata

val QUALITIES_LIST = listOf(
    "native" to "Opus Nativo (~160k)",
    "m4a" to "M4A AAC (128k / 256k Premium)"
)

private val QUICK_DISCOVERY_TAGS = listOf(
    "🔥 Éxitos de Hoy" to "top hits 2024",
    "🎧 Lo-Fi Chill" to "lofi hip hop radio chill beats",
    "🎸 Rock Clásico" to "classic rock greatest hits",
    "🎹 Piano & Relax" to "calm piano relaxing music",
    "💃 Pop Latino" to "exitos pop latino",
    "⚡ Electronic & EDM" to "edm festival hits",
    "🎷 Jazz Lounge" to "smooth jazz coffee"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(top = 12.dp)
    ) {
        // Cabecera Apple con Gran Título y subtítulo elegante
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                text = "Explorar",
                color = TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Búsqueda y descarga directa desde YouTube Music",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Barra de búsqueda Glassmorphism estilo iOS Spotlight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .glassmorphism(
                    shape = RoundedCornerShape(18.dp),
                    backgroundColor = Color(0xF21C1C1E),
                    borderColor = Color(0x33FFFFFF),
                    borderWidth = 1.dp
                )
        ) {
            TextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                placeholder = {
                    Text(
                        "Buscar canciones, artistas o enlaces de YouTube...",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = AppleRose
                    )
                },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Limpiar",
                                tint = TextSecondary
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    viewModel.performSearch()
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Alerta o mensaje de estado
        uiState.statusMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .glassmorphism(
                        shape = RoundedCornerShape(12.dp),
                        backgroundColor = Color(0x66FF453A),
                        borderColor = Color(0xFFFF453A)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = msg,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.clearStatusMessage() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Selector de calidad de audio (Chips estilo Apple)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(QUALITIES_LIST) { (id, label) ->
                val isSelected = uiState.selectedQuality == id
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) AppleRose else Color(0x1AFFFFFF),
                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF)),
                    modifier = Modifier.clickable { viewModel.onQualityChange(id) }
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Contenido de búsqueda / Resultados
        if (uiState.isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = AppleRose,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Consultando Innertube en Rust...",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else if (uiState.searchResults.isEmpty()) {
            // Pantalla de descubrimiento inicial con sugerencias rápidas
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Tendencias y Descubrimiento",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Tags de búsqueda rápida con respuesta instantánea
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_DISCOVERY_TAGS.chunked(2).forEach { rowTags ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowTags.forEach { (title, searchQuery) ->
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            viewModel.onQueryChange(searchQuery)
                                            focusManager.clearFocus()
                                            viewModel.performSearch()
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0x261C1C1E),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1FFFFFFF))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = title,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (rowTags.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 145.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.searchResults, key = { it.id }) { track ->
                    SearchResultCard(
                        track = track,
                        downloadProgress = uiState.activeDownloads[track.id],
                        onDownload = { viewModel.startDownload(track) }
                    )
                }
            }
        }
    }
}

@Composable
fun SearchResultCard(
    track: TrackMetadata,
    downloadProgress: uniffi.ytmusic_core.DownloadProgress?,
    onDownload: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .applePressEffect(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF018181B),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Carátula de alta resolución con esquinas redondeadas
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33FFFFFF))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${track.artist} • ${formatDurationSec(track.durationSec)}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Botón de descarga con estados animados
                val isCompleted = downloadProgress?.state == "completed"
                val isDownloading = downloadProgress != null && !isCompleted && downloadProgress.state != "error"

                Surface(
                    onClick = onDownload,
                    enabled = !isDownloading,
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        isCompleted -> Color(0xFF30D158)
                        isDownloading -> Color(0x33FA2D48)
                        else -> Color(0x26FFFFFF)
                    },
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                progress = { (downloadProgress.progressPercent / 100f).coerceIn(0f, 1f) },
                                color = AppleRose,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${downloadProgress.progressPercent.toInt()}%",
                                color = AppleRose,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else if (isCompleted) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Descargar",
                                tint = TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Indicador de progreso de descarga si está activa
            if (downloadProgress != null && downloadProgress.state != "completed") {
                if (downloadProgress.state == "error") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = downloadProgress.errorMessage ?: "Error en la descarga",
                        color = Color(0xFFFF453A),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (downloadProgress.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AppleRose,
                        trackColor = Color(0x33FFFFFF)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (downloadProgress.state) {
                                "downloading" -> "Descargando chunks a 35 MB/s..."
                                "tagging" -> "Incrustando etiquetas y metadatos..."
                                else -> downloadProgress.state
                            },
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "${String.format("%.1f", downloadProgress.speedMbS)} MB/s",
                            color = AppleCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

fun formatDurationSec(sec: UInt): String {
    val m = sec / 60u
    val s = sec % 60u
    return String.format("%d:%02d", m.toInt(), s.toInt())
}
