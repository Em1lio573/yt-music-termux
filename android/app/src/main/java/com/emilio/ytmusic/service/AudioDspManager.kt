package com.emilio.ytmusic.service

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

data class PresetData(
    val name: String,
    val gains: List<Int>, // en decibelios o milibelios
    val bassBoost: Short
)

class AudioDspManager {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var attachedSessionId: Int = -1

    val presets = mapOf(
        "flat" to PresetData("Plano", listOf(0, 0, 0, 0, 0), 0),
        "bass_boost" to PresetData("Refuerzo de Graves", listOf(7, 5, 0, 1, 2), 400),
        "rock" to PresetData("Rock", listOf(5, 3, -1, 3, 5), 200),
        "pop" to PresetData("Pop", listOf(-1, 2, 4, 2, -1), 100),
        "electronic" to PresetData("Electrónica", listOf(6, 4, 0, 3, 4), 300),
        "acoustic" to PresetData("Acústico", listOf(3, 2, 1, 3, 3), 0),
        "vocal" to PresetData("Voz / Podcast", listOf(-3, 1, 4, 3, -1), 0),
        "jazz" to PresetData("Jazz", listOf(3, 1, 2, 2, 3), 100)
    )

    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        // Evitar destruir y recrear el DSP en cada seek (eso provoca picos bruscos de volumen en AudioFlinger)
        if (audioSessionId == attachedSessionId && equalizer != null) {
            return
        }
        try {
            release()
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = false // Activar solo cuando el usuario elija un preset distinto de plano
            }
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = false
            }
            attachedSessionId = audioSessionId
            Log.i("AudioDspManager", "DSP nativo vinculado a audioSessionId: $audioSessionId")
        } catch (e: Exception) {
            Log.w("AudioDspManager", "No se pudo inicializar DSP nativo: ${e.message}")
        }
    }

    fun setBandGain(bandIndex: Int, gainDb: Int) {
        val eq = equalizer ?: return
        if (!eq.enabled) eq.enabled = true
        if (bandIndex in 0 until eq.numberOfBands.toInt()) {
            val millibels = (gainDb * 100).toShort()
            val minRange = eq.bandLevelRange[0]
            val maxRange = eq.bandLevelRange[1]
            val clamped = millibels.coerceIn(minRange, maxRange)
            eq.setBandLevel(bandIndex.toShort(), clamped)
        }
    }

    fun setBassBoost(level0to10: Int) {
        val bb = bassBoost ?: return
        val strength = (level0to10.coerceIn(0, 10) * 100).toShort()
        bb.enabled = strength > 0
        if (bb.strengthSupported) {
            bb.setStrength(strength)
        }
    }

    fun applyPreset(presetKey: String) {
        val p = presets[presetKey] ?: return
        val isFlat = presetKey == "flat"
        equalizer?.enabled = !isFlat
        p.gains.forEachIndexed { index, gain ->
            if (!isFlat) setBandGain(index, gain)
        }
        val bb = bassBoost ?: return
        bb.enabled = p.bassBoost > 0
        if (bb.strengthSupported) {
            bb.setStrength(p.bassBoost)
        }
    }

    fun release() {
        equalizer?.release()
        equalizer = null
        bassBoost?.release()
        bassBoost = null
        attachedSessionId = -1
    }
}
