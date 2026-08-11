package com.blaze.player.playback

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.playbackPreferences by preferencesDataStore(name = "playback_settings")

/** The one process-wide persisted playback speed source of truth. */
object PlaybackSpeedStore {
    private val speedKey = floatPreferencesKey("global_playback_speed")
    const val DEFAULT = 1f
    const val MIN = .25f
    const val MAX = 4f

    fun observe(context: Context): Flow<Float> = context.playbackPreferences.data
        .map { prefs -> normalize(prefs[speedKey]) ?: DEFAULT }
        // A damaged/unreadable preferences file must not terminate the service
        // collector or playback. The next successful read will restore the
        // persisted value; read failures use the same safe default as corruption.
        .catch { error ->
            if (error is IOException) emit(DEFAULT) else throw error
        }

    suspend fun save(context: Context, value: Float) {
        val normalized = normalize(value) ?: DEFAULT
        context.playbackPreferences.edit { it[speedKey] = normalized }
    }

    /** Corrupt, non-finite, or out-of-range values fall back to the safe default. */
    fun normalize(value: Float?): Float? = value?.takeIf { it.isFinite() && it in MIN..MAX }
}
