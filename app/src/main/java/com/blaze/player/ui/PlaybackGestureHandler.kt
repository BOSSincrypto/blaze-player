package com.blaze.player.ui

import kotlin.math.abs

/** Pure gesture state machine. UI/system side effects are supplied as callbacks. */
class PlaybackGestureHandler(
    private val duration: () -> Long,
    private val position: () -> Long,
    private val brightness: () -> Float,
    private val volume: () -> Float,
    private val seek: (Long) -> Unit,
    private val setBrightness: (Float) -> Unit,
    private val setVolume: (Float) -> Unit,
    private val showOverlay: (String) -> Unit,
    private val clearOverlay: () -> Unit
) {
    enum class Kind { SEEK, BRIGHTNESS, VOLUME }
    private var kind: Kind? = null
    private var startX = 0f
    private var startY = 0f
    private var width = 1f
    private var height = 1f
    private var startPosition = 0L
    private var startLevel = 0f
    private val coalescer = SeekCoalescer(seek)

    fun down(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        startX = x; startY = y
        width = viewWidth.coerceAtLeast(1f); height = viewHeight.coerceAtLeast(1f)
        kind = null
        startPosition = PlaybackMath.position(position(), duration())
        startLevel = if (x < width / 2f) PlaybackMath.clamp(brightness()) else PlaybackMath.clamp(volume())
    }

    fun move(x: Float, y: Float) {
        val dx = x - startX
        val dy = y - startY
        if (kind == null && maxOf(abs(dx), abs(dy)) < 8f) return
        if (kind == null) kind = if (abs(dx) >= abs(dy)) Kind.SEEK else if (startX < width / 2f) Kind.BRIGHTNESS else Kind.VOLUME
        when (kind) {
            Kind.SEEK -> {
                val target = PlaybackMath.seekByDelta(startPosition, dx, width, duration())
                coalescer.offer(target)
                showOverlay("Seek ${target / 1000}s")
            }
            Kind.BRIGHTNESS -> {
                setBrightness(PlaybackMath.verticalLevel(dy, height, startLevel))
                showOverlay("Brightness ${(PlaybackMath.verticalLevel(dy, height, startLevel) * 100).toInt()}%")
            }
            Kind.VOLUME -> {
                setVolume(PlaybackMath.verticalLevel(dy, height, startLevel))
                showOverlay("Volume ${(PlaybackMath.verticalLevel(dy, height, startLevel) * 100).toInt()}%")
            }
            null -> Unit
        }
    }

    fun up() {
        if (kind == Kind.SEEK) coalescer.release()
        kind = null
        clearOverlay()
    }
}
