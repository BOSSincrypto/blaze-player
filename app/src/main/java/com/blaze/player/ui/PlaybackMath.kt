package com.blaze.player.ui

import kotlin.math.roundToLong

object PlaybackMath {
    fun progress(positionMs: Long, durationMs: Long): Float = if (durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
    fun position(positionMs: Long, durationMs: Long): Long = positionMs.coerceAtLeast(0L).let { if (durationMs > 0L) it.coerceAtMost(durationMs) else it }
    fun seekTarget(fraction: Float, durationMs: Long): Long = if (durationMs <= 0L) 0L else (fraction.toDouble().takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, 1.0).let { (it * durationMs).roundToLong().coerceIn(0L, durationMs) }
    fun clamp(value: Float, min: Float = 0f, max: Float = 1f): Float = value.takeIf { it.isFinite() }?.coerceIn(min, max) ?: min
    fun verticalLevel(deltaY: Float, height: Float): Float = clamp(
        0.5f - (deltaY.takeIf { it.isFinite() } ?: 0f) / height.coerceAtLeast(1f)
    )
}

class SeekCoalescer(private val dispatch: (Long) -> Unit) {
    private var latest: Long? = null
    fun offer(targetMs: Long) { latest = targetMs.coerceAtLeast(0L) }
    fun release() { latest?.let(dispatch); latest = null }
}

object PlaybackSpeed {
    val presets = listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    fun validate(value: Float): Float? = value.takeIf { it.isFinite() && it in .25f..4f }
}
