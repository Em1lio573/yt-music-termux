package com.emilio.ytmusic.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.emilio.ytmusic.ui.theme.AppleRose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.ytmusic_core.LocalTrack
import java.io.File

@Composable
fun TrackArtwork(
    track: LocalTrack?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    placeholderIconSize: Dp = 24.dp,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (track == null) {
        PlaceholderArtwork(modifier = modifier, shape = shape, iconSize = placeholderIconSize)
        return
    }

    val context = LocalContext.current

    // 1. Verificar caché privada de la app (invisible para la Galería de Android) o coverPath existente
    val privateCacheFile = remember(track.filePath) {
        val coversDir = File(context.cacheDir, "covers")
        val key = track.filePath.hashCode().toUInt().toString(16)
        File(coversDir, "art_$key.jpg")
    }

    val validCoverFile = remember(track.filePath, track.coverPath) {
        when {
            privateCacheFile.exists() && privateCacheFile.length() > 0 -> privateCacheFile
            !track.coverPath.isNullOrBlank() -> {
                val f = File(track.coverPath!!)
                if (f.exists() && f.length() > 0) f else null
            }
            else -> null
        }
    }

    if (validCoverFile != null) {
        AsyncImage(
            model = validCoverFile,
            contentDescription = track.title,
            contentScale = contentScale,
            modifier = modifier
                .clip(shape)
                .background(Color(0x3318181B))
        )
    } else {
        // 2. Extraer carátula incrustada mediante MediaMetadataRetriever y cachearla en cacheDir privado
        var embeddedBitmap by remember(track.filePath) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(track.filePath) {
            withContext(Dispatchers.IO) {
                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(track.filePath)
                    val rawPic = mmr.embeddedPicture
                    mmr.release()

                    if (rawPic != null && rawPic.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(rawPic, 0, rawPic.size)
                        embeddedBitmap = bmp

                        // Guardar ÚNICAMENTE en cacheDir privado de la app (nunca aparece en la Galería)
                        try {
                            privateCacheFile.parentFile?.mkdirs()
                            if (!privateCacheFile.exists()) {
                                privateCacheFile.writeBytes(rawPic)
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        if (embeddedBitmap != null) {
            Image(
                bitmap = embeddedBitmap!!.asImageBitmap(),
                contentDescription = track.title,
                contentScale = contentScale,
                modifier = modifier
                    .clip(shape)
                    .background(Color(0x3318181B))
            )
        } else {
            PlaceholderArtwork(modifier = modifier, shape = shape, iconSize = placeholderIconSize)
        }
    }
}

@Composable
fun PlaceholderArtwork(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    iconSize: Dp = 24.dp
) {
    Surface(
        modifier = modifier.clip(shape),
        color = Color(0x33FA2D48)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = AppleRose,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
