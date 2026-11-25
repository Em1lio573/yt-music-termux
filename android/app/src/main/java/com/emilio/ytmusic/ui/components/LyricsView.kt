package com.emilio.ytmusic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilio.ytmusic.ui.theme.AppleRose
import com.emilio.ytmusic.ui.theme.TextMuted
import com.emilio.ytmusic.ui.theme.TextPrimary
import uniffi.ytmusic_core.LyricsData

@Composable
fun LyricsView(
    lyricsData: LyricsData?,
    currentLyricIndex: Int,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (lyricsData == null || (lyricsData.lines.isEmpty() && lyricsData.plainText.isEmpty())) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Letras no disponibles para esta canción",
                color = TextMuted,
                fontSize = 15.sp
            )
        }
        return
    }

    if (!lyricsData.isSynced) {
        // Letra en texto plano
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(lyricsData.plainText.lines().size) { idx ->
                val line = lyricsData.plainText.lines()[idx]
                Text(
                    text = line,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 26.sp
                )
            }
        }
        return
    }

    // Letra sincronizada con desplazamiento automático
    val listState = rememberLazyListState()

    LaunchedEffect(currentLyricIndex) {
        if (currentLyricIndex >= 0 && currentLyricIndex < lyricsData.lines.size) {
            listState.animateScrollToItem(
                index = (currentLyricIndex - 2).coerceAtLeast(0)
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        itemsIndexed(lyricsData.lines) { index, line ->
            val isActive = index == currentLyricIndex

            val textColor by animateColorAsState(
                targetValue = if (isActive) AppleRose else Color(0x66FFFFFF),
                label = "lyricTextColor"
            )
            val scale by animateFloatAsState(
                targetValue = if (isActive) 1.05f else 1.0f,
                label = "lyricScale"
            )

            Text(
                text = line.text,
                color = textColor,
                fontSize = if (isActive) 24.sp else 20.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                lineHeight = 32.sp,
                modifier = Modifier
                    .scale(scale)
                    .clickable { onSeekTo(line.timestampMs.toLong()) }
            )
        }
    }
}
