package com.emilio.ytmusic.storage

import android.content.Context
import uniffi.ytmusic_core.LocalTrack
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Gestor persistente de estadísticas de reproducción y motores de reproducción aleatoria:
 * 1. Aleatorio puro (Fisher-Yates sin repeticiones).
 * 2. Aleatorio Inteligente (muestreo aleatorio ponderado Efraimidis-Spirakis por canciones
 *    más escuchadas + enfriamiento por recencia + dispersión de artistas).
 */
class PlayStatsManager private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ytmusic_play_stats", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: PlayStatsManager? = null

        fun getInstance(context: Context): PlayStatsManager {
            return instance ?: synchronized(this) {
                instance ?: PlayStatsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun keyFor(filePath: String): String = filePath.hashCode().toUInt().toString(16)

    fun recordPlay(filePath: String) {
        val k = keyFor(filePath)
        val count = prefs.getInt("c_$k", 0) + 1
        prefs.edit()
            .putInt("c_$k", count)
            .putLong("t_$k", System.currentTimeMillis())
            .apply()
    }

    fun getPlayCount(filePath: String): Int {
        return prefs.getInt("c_${keyFor(filePath)}", 0)
    }

    fun getLastPlayedAt(filePath: String): Long {
        return prefs.getLong("t_${keyFor(filePath)}", 0L)
    }

    /**
     * Algoritmo Fisher-Yates (aleatorio uniforme sin repeticiones).
     */
    fun fisherYatesShuffle(tracks: List<LocalTrack>, pinnedFirst: LocalTrack? = null): List<LocalTrack> {
        if (tracks.size <= 1) return tracks
        val pool = tracks.filter { pinnedFirst == null || it.filePath != pinnedFirst.filePath }.toMutableList()
        for (i in pool.lastIndex downTo 1) {
            val j = Random.nextInt(i + 1)
            val tmp = pool[i]
            pool[i] = pool[j]
            pool[j] = tmp
        }
        return if (pinnedFirst != null) listOf(pinnedFirst) + pool else pool
    }

    /**
     * Aleatorio Inteligente (Efraimidis-Spirakis Weighted Random Sampling sin reemplazo):
     * - Da mayor probabilidad de salir antes a tus canciones más escuchadas (peso = 1 + ln(1 + plays) * 3.0).
     * - Mantiene aleatoriedad real para que canciones nuevas o menos oídas también aparezcan.
     * - Aplica enfriamiento si sonó en los últimos 10 minutos para evitar repeticiones inmediatas.
     * - Evita poner dos canciones seguidas del mismo artista cuando hay variedad disponible.
     */
    fun smartWeightedShuffle(tracks: List<LocalTrack>, pinnedFirst: LocalTrack? = null): List<LocalTrack> {
        if (tracks.size <= 1) return tracks
        val now = System.currentTimeMillis()
        val cooldownWindowMs = 10 * 60 * 1000L // 10 minutos

        val candidates = tracks.filter { pinnedFirst == null || it.filePath != pinnedFirst.filePath }
        val scored = candidates.map { track ->
            val plays = getPlayCount(track.filePath)
            val lastPlayed = getLastPlayedAt(track.filePath)

            // Peso base 1.0 + impulso logarítmico por reproducciones (las más escuchadas suben fuerte sin monopolizar)
            var weight = 1.0 + ln(1.0 + plays.toDouble()) * 3.0

            // Enfriamiento por recencia si acaba de sonar
            if (lastPlayed > 0L && (now - lastPlayed) < cooldownWindowMs) {
                weight *= 0.25
            }

            // Clave aleatoria ponderada u^(1/w): mayor peso -> estadísticamente mayor clave, siempre aleatorio
            val u = Random.nextDouble(0.0001, 1.0)
            val key = u.pow(1.0 / weight.coerceAtLeast(0.1))
            track to key
        }.sortedByDescending { it.second }.map { it.first }.toMutableList()

        // Dispersión ligera para evitar repetir exactamente el mismo artista en canciones consecutivas
        val spread = mutableListOf<LocalTrack>()
        var lastArtist = pinnedFirst?.artist?.lowercase() ?: ""

        while (scored.isNotEmpty()) {
            val pickIdx = scored.indexOfFirst { it.artist.lowercase() != lastArtist }
            val chosen = if (pickIdx in 0..4) {
                scored.removeAt(pickIdx)
            } else {
                scored.removeAt(0)
            }
            spread.add(chosen)
            lastArtist = chosen.artist.lowercase()
        }

        return if (pinnedFirst != null) listOf(pinnedFirst) + spread else spread
    }
}
