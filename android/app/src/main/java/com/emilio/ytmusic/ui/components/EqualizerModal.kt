package com.emilio.ytmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emilio.ytmusic.ui.theme.*
import com.emilio.ytmusic.viewmodel.PlayerUiState

val PRESETS_LIST = listOf(
    "flat" to "Plano",
    "bass_boost" to "Refuerzo Graves",
    "rock" to "Rock",
    "pop" to "Pop",
    "electronic" to "Electrónica",
    "acoustic" to "Acústico",
    "vocal" to "Voz / Podcast",
    "jazz" to "Jazz"
)

@Composable
fun EqualizerModal(
    uiState: PlayerUiState,
    onPresetSelected: (String) -> Unit,
    onBassBoostChanged: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xF218181B),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ecualizador & Audio DSP",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Presets de Audio",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(PRESETS_LIST) { (key, label) ->
                    val isSelected = uiState.currentPreset == key
                    FilterChip(
                        selected = isSelected,
                        onClick = { onPresetSelected(key) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppleRose,
                            selectedLabelColor = TextPrimary,
                            containerColor = Color(0x1AFFFFFF),
                            labelColor = TextSecondary
                        ),
                        border = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Control de Bass Boost
            Text(
                text = "Bass Booster (${uiState.bassBoost}/10)",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Slider(
                value = uiState.bassBoost.toFloat(),
                onValueChange = { onBassBoostChanged(it.toInt()) },
                valueRange = 0f..10f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = AppleRose,
                    activeTrackColor = AppleRose,
                    inactiveTrackColor = Color(0x33FFFFFF)
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Procesamiento de señal nativo de 5 bandas activo en Android AudioFlinger",
                color = TextMuted,
                fontSize = 11.sp
            )
        }
    }
}
